# seedy

a fabric mod for minecraft 26.2 and 26.3, built with stonecutter. the compact dear imgui panel is rendered inside minecraft's window by a jni-loaded native library. the world tab controls buried-treasure, mob-spawner, and matching-loot chest outlines.

## install and use

put the matching jar from `artifacts/` in your mods folder. use java 25, fabric loader 0.19.3 or newer, and fabric api 0.161.0 for your minecraft version. remove any older seedy jar before installing this one.

press f8 in a world to toggle the panel, or run `/seedy`. change the key in options → controls → key binds → seedy. escape also closes the panel. drag its top header to move it and its bottom-right corner to resize it. the jar contains a universal macos dylib and a windows x64 dll. the panel and outlines use minecraft's vulkan renderer exclusively. select vulkan in video settings and restart minecraft. the native library shares minecraft's vulkan device and graphics queue; it does not create another window or graphics context.

the recovery tab lists detected structures, start chunks, evidence, and search progress. collection recognizes desert-pyramid traps, swamp-hut interiors, shipwrecks, and trial-chamber starting rooms. shipwreck and trial detection uses the target version's vanilla templates, including shipwreck wood palettes and all rotations. the starting room of a trial chamber must be loaded; a random corridor or spawner is not enough to determine its start chunk. automatic recovery starts at five independent observations and 40 evidence bits. more observations help narrow the search. trial chambers provide fewer lower-bit constraints than shipwrecks or pyramids, so a search may ask for more evidence even after meeting the minimum. the engine uses them both alone and in mixed collections. manual entry accepts supported uniform-placement structures; enter the actual start chunk, not the chunk you happen to be standing in.

enter a signed 64-bit seed in the seed tab, or recover it from observations. the locate tab searches vanilla structures and biomes in the overworld, nether, and end. choose a dimension to see its available targets. nether fortresses and bastion remnants can be searched separately; nether complexes returns both. coordinates and distances use blocks in that dimension; use position is disabled while you are in a different dimension, and coordinates are not portal-scaled. you can cancel searches and copy coordinates. changing the search inputs clears the previous results. type in target dropdowns to filter names. use save beside a result to keep it in the saved tab, where distance and compass direction update as you move in the same dimension.

## seed map

seedy opens as a larger floating window inside minecraft. resize from any edge or corner, drag the header to move it, and use expand/restore or double-click the header to maximize it inside the game. settings offers compact and large layouts, opacity, sidebar width, and map defaults. size, position, selected tab, and display settings save to `config/seedy/ui.json` and are restored next time.

the map has a collapsible sidebar with a draggable divider. choose fast, balanced, or detailed sampling; detailed allows more tiles before reducing resolution. toggle biome and structure names, player markers, coordinate or chunk grids, and adjust marker size and scroll sensitivity. the biome legend lists visible biomes; click one to highlight it. follow keeps the map centered on your player until you pan or scroll. smaller windows stack the controls above the map.

the zoom slider and six presets cover 0.125 to 1,024 blocks per screen pixel. shift-scroll gives finer zoom steps. close-up views show structure coordinates, while the footer reports visible area and actual biome sampling distance. vanilla biome sampling has a 4-block grid; zooming further magnifies that grid. right-click the map to copy coordinates, center a position, or zoom there. chunk lines appear once they are far enough apart to read. the fast, balanced, and detailed modes allow up to 64, 128, and 256 visible biome tiles respectively, with a bounded cache of 512 tiles.

the map tab shows biome colors and a selectable structure layer for the overworld, nether, and end. drag the map to pan, scroll to zoom around the cursor, or use the plus and minus buttons. enter x and z and press enter to jump somewhere; player brings you back to your position in the selected dimension. hover for biome names and structure coordinates, then click to select a location and copy it. end cities can be filtered by elytra ship, with ship markers shown in gold.

the colors show a biome slice at the chosen y, not terrain elevation or a surface-height map. change y to inspect underground biomes. small biomes can disappear at wider zoom levels; zoom in for finer sampling. dots use the same vanilla structure-generation checks as locate and appear only at closer zoom levels. selecting a different layer changes the structure dots without resampling cached biomes.

visible tiles load on one low-priority background worker, with a short delay after navigation. recent tiles are cached with fixed limits, and leaving the tab or closing the panel cancels pending work. the map reuses the vulkan panel renderer, without a browser or separate graphics context. it predicts vanilla generation without loading world chunks. village dots use minecraft's validated starting piece and terrain/biome checks without building every street and house; other structure dots generate their pieces, including end-city ships and stronghold portal rooms.

## end cities

choose end → structures → end cities. the end cities filter offers all cities, with elytra ship, and without elytra ship. seedy generates the city pieces and checks the elytra marker in the ship template instead of estimating ship presence from the placement grid. each result shows its ship status, and copy elytra copies the generated item-frame coordinates. city results include height. this predicts fresh generation; it cannot tell whether another player has already taken an elytra.

## chest loot

choose ancient cities or desert pyramids in locate, then enable predict chest loot. add an item and its minimum total per structure. for example, enchanted golden apple with a minimum of 3 shows structures with at least three predicted apples across their chests. you can combine up to eight different items; every minimum must be met. remove all filters to browse every structure's chests. select a result's coordinates to see item totals and each chest's contents, including enchantments and durability. copy chest copies its coordinates.

purple outlines mark loaded chests containing your selected items in matching search results. they appear when the chunk loads, disappear when the chest is broken or unloaded, and clear when the seed or search changes. ancient-city chests use their full generated coordinates; desert-temple chest heights are resolved from the loaded treasure-room blocks. the original blue and gold colors are unchanged, and outlines have no black backing stroke.

predictions use the current version's vanilla templates, decoration random sequence, loot tables, and enchantment functions. version 0.5.1 evaluates loot pools directly, fixing the null server-level error caused by fabric's server-only loot-table events during client-side predictions. they describe fresh, unopened chests with normal player luck. already looted chests, changed loot tables, and older generated chunks can differ. a sculk feature can consume a terrain-dependent number of random draws before a later chest in the same chunk. those affected chests are marked as unavailable and excluded from totals rather than given guessed contents; in those cases the displayed total is a minimum, and the filter can miss a structure whose remaining items are in an unavailable chest. chests in ice boxes and the fixed chest in applicable city-center templates are included. no chest is opened or modified to generate a prediction.

## saved progress

observations, collection settings, seeds, and saved locations are stored under `config/seedy/sessions/`. files are separated by minecraft version and the world seed hash. no server address is stored. progress saves every five seconds and when disconnecting or closing minecraft; the footer shows its status. damaged save files are left unchanged. saved locations are shown only for the current seed.

loaded chunks are checked immediately, and matching observations appear in the panel without waiting for the rescan queue. neighboring templates are retried as each chunk arrives. rescan checks currently loaded overworld chunks again within a per-tick time budget. detection still needs the recognizable part of a structure: missing or unloaded blocks never count as evidence.

the world tab lists mob spawners and buried-treasure candidates in loaded chunks. blue outlines mark spawners; gold outlines mark treasure candidates. they remain visible through blocks within 512 blocks and can be toggled separately. unloading chunks, changing dimensions, and breaking blocks remove their outlines. treasure detection checks the single chest at local chunk position 9,9, its biome, waterlogging, and supporting terrain. these are candidates, since a player-placed chest can match the same pattern. this feature does not read unopened chest contents or add treasure/spawners as recovery evidence.

## recovery engine

seedy owns the observation store, random-number implementation, bit lifting, candidate search, cancellation, and seed-hash verification. seedcrackerx and the seedfinding libraries are not runtime dependencies or bundled engines.

the algorithm filters 19 lower structure-seed bits, searches the remaining 29 bits, and checks the upper 16 world-seed bits against the server's sha-256 seed hash. a separate inverse-random search handles rejected draws that ordinary low-bit filtering can miss. candidates are checked against every observation.

the research reference is [seedcrackerx](https://github.com/19misterx98/seedcrackerx), revision `b72a829f768967fa737a4f1f63b32e37b9619152`. its source and license remain under `vendor/` for reference only. placement metadata and biome generation come from the target minecraft version.

## limits

structure searches apply vanilla placement, frequency, exclusion, weighted selection, valid-biome, and structure-start generation checks. the actual structure pieces are generated locally without creating terrain chunks. coordinates point to the first generated piece, not an unchecked candidate chunk. ancient cities and trial chambers include their generated underground height. other structures omit y because placement-time terrain adjustments can change it. results identify the structure's dimension. strongholds use vanilla concentric-ring placement, including biome relocation, and return the generated portal room with its y coordinate.

[chunkbase's seed map](https://www.chunkbase.com/apps/seed-map) was used as a reference for result semantics and limitations. the locator runs minecraft's version-specific world-generation code locally. worlds upgraded from older versions and custom data packs may differ from the current version's vanilla predictions.

biomes in each selected dimension are sampled every 64 blocks at the chosen y, with an 8,192-block radius limit and at most 128 results. custom generation can invalidate predictions. modified or looted structures may not be detected. custom generation, older generated chunks, and structures removed or edited by players can differ from fresh vanilla generation.

## build and checks

run `bash scripts/build-macos.sh` on macos, or `powershell -file scripts/build-windows.ps1` from a visual studio x64 developer shell. then run `./gradlew :26.2:build :26.3:build`.

tests compare seedy's placement and hash calculations with minecraft, recover seeds from shipwreck-only, trial-only, and mixed observations, and check every shipwreck template palette and rotation, both trial start rooms, missing blocks, session persistence, random-draw rejection, cancellation, bounds, coordinate scaling, biomes, generated structure locations, stronghold portal rooms, treasure filtering, and outline clipping. seed `2589511696370800371` is covered by an ancient-city regression test on 26.2. run `ctest --test-dir native/build-overlay --output-on-failure` on macos for native clicking, text input, navigation, rescanning, saving and removing locations, copying, dragging, closing, outline toggles, loot filters, chest details, and unchanged outline colors. loot checks compare chest seeds against vanilla structure placement, compare item contents against vanilla loot-table codecs, and cover filter boundaries and chunk borders. the windows dll can be cross-compiled here; native windows execution and live exploration-to-seed verification still need testing. the vulkan backend uses dear imgui and volk, with their licenses bundled in the jar.

the development loot smoke check launches a real fabric client, verifies that fabric loot mixins are active, rolls all three supported chest tables for 128 seeds each, and runs the ancient-city item filter. it also checks that prediction does not invoke server loot events.

map checks cover negative tile coordinates, sampling bounds, seed/dimension/height changes, cancellation, cache reuse, end-city ship markers, and village starting points compared with full vanilla generation. native interaction checks cover panning, cursor-centered zoom, selection copying, dimension changes, and stopping work when leaving the map. the development smoke check also generates map tiles and structure dots in all three dimensions.

window checks cover preference round-trips, invalid values, damaged settings files, edge resizing, maximize/restore, sidebar resizing, zoom presets, detail and grid settings, and saving when the panel closes. the development smoke check also verifies that the native map requests and receives real biome tiles.
