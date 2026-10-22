from pathlib import Path
import re
import sys


source = Path(sys.argv[1]).read_text()
output = Path(sys.argv[2])

replacements = [
    (
        '#include "java_helper.h"\n',
        '#include "java_helper.h"\n#include <algorithm>\n#include <atomic>\n#include <chrono>\n#include <system_error>\n#include <thread>\n#include <vector>\n',
    ),
    (
        'using simd = stdx::native_simd<uint64_t>;',
        '#if defined(__aarch64__)\nusing simd = stdx::fixed_size_simd<uint64_t, 8>;\n#else\nusing simd = stdx::native_simd<uint64_t>;\n#endif',
    ),
    (
        'static uint64_t nextlong1, nextlong2;',
        'static uint64_t nextlong1, nextlong2;\nstatic std::atomic<bool> foundWorldSeed{false};',
    ),
    (
        '\t\t\tsendUpdate("commands.bedrockcrack.foundWorldSeed", false, unstafford13(lo) ^ 7640891576956012809);',
        '\t\t\tuint64_t seed = unstafford13(lo) ^ 7640891576956012809;\n'
        '\t\t\tif(matchesWorldSeed(seed) && !foundWorldSeed.exchange(true, std::memory_order_relaxed))\n'
        '\t\t\t\tsendUpdate("commands.bedrockcrack.foundWorldSeed", false, seed);',
    ),
    (
        '#pragma omp parallel for schedule(dynamic, 32768)\n'
        '\tfor(uint64_t i = 0; i < uint64_t{1} << 40; i += SIMD_BITS) {\n'
        '\t\tbool x = bruteKernel<false>(i);\n'
        '\t\tif(x) [[unlikely]] {\n'
        '\t\t\tbruteKernel<true>(i);\n'
        '\t\t}\n'
        '\t}\n',
        '\tfoundWorldSeed.store(false, std::memory_order_relaxed);\n'
        '\tconstexpr uint64_t limit = uint64_t{1} << 40;\n'
        '\tconstexpr uint64_t batchSize = SIMD_BITS * 32768;\n'
        '\tstd::atomic<uint64_t> nextGuess{0};\n'
        '\tstd::atomic<uint64_t> completed{0};\n'
        '\tstd::atomic<int64_t> lastUpdate{0};\n'
        '\tsendUpdate("commands.bedrockcrack.searchProgress", false, uint64_t{0}, limit);\n'
        '\tif(!shouldContinueSearch()) return;\n'
        '\tauto work = [&] {\n'
        '\t\tuint64_t localCompleted = 0;\n'
        '\t\twhile(!foundWorldSeed.load(std::memory_order_relaxed)) {\n'
        '\t\t\tuint64_t start = nextGuess.fetch_add(batchSize, std::memory_order_relaxed);\n'
        '\t\t\tif(start >= limit) break;\n'
        '\t\t\tuint64_t end = std::min(start + batchSize, limit);\n'
        '\t\t\tfor(uint64_t i = start; i < end; i += SIMD_BITS) {\n'
        '\t\t\t\tif(bruteKernel<false>(i)) [[unlikely]] bruteKernel<true>(i);\n'
        '\t\t\t}\n'
        '\t\t\tlocalCompleted += end - start;\n'
        '\t\t\tif(localCompleted >= (uint64_t{1} << 25)) {\n'
        '\t\t\t\tuint64_t current = completed.fetch_add(localCompleted, std::memory_order_relaxed) + localCompleted;\n'
        '\t\t\t\tlocalCompleted = 0;\n'
        '\t\t\t\tauto now = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();\n'
        '\t\t\t\tauto previous = lastUpdate.load(std::memory_order_relaxed);\n'
        '\t\t\t\tif(now - previous >= 1000 && lastUpdate.compare_exchange_strong(previous, now, std::memory_order_relaxed)) {\n'
        '\t\t\t\t\tsendUpdate("commands.bedrockcrack.searchProgress", false, current, limit);\n'
        '\t\t\t\t\tif(!shouldContinueSearch()) foundWorldSeed.store(true, std::memory_order_relaxed);\n'
        '\t\t\t\t}\n'
        '\t\t\t}\n'
        '\t\t}\n'
        '\t\tif(localCompleted) completed.fetch_add(localCompleted, std::memory_order_relaxed);\n'
        '\t};\n'
        '\tunsigned int cores = std::thread::hardware_concurrency();\n'
        '\tunsigned int workerCount = std::clamp(cores > 1 ? cores - 1 : 1u, 1u, 32u);\n'
        '\tstd::vector<std::thread> workers;\n'
        '\tworkers.reserve(workerCount - 1);\n'
        '\tfor(unsigned int i = 1; i < workerCount; ++i) {\n'
        '\t\ttry { workers.emplace_back(work); }\n'
        '\t\tcatch(const std::system_error&) { break; }\n'
        '\t}\n'
        '\twork();\n'
        '\tfor(auto& worker : workers) worker.join();\n'
        '\tsendUpdate("commands.bedrockcrack.searchProgress", false, completed.load(std::memory_order_relaxed), limit);\n',
    ),
]

for previous, updated in replacements:
    if source.count(previous) != 1:
        raise SystemExit(f"expected one source match, found {source.count(previous)}")
    source = source.replace(previous, updated, 1)

if "--reference" not in sys.argv[3:]:
    assignments = list(re.finditer(r"^\s*(S[12]_(?:lo|hi|ca|is|cb)\[\d+\])\s*=\s*(.*);$", source, re.MULTILINE))
    positions = {match[1]: index for index, match in enumerate(assignments)}
    if len(assignments) != 640 or len(positions) != 640:
        raise SystemExit("unexpected inverse circuit assignments")
    checks = []
    for match in assignments:
        carry = re.fullmatch(r"(S[12])_c([ab])\[(\d+)\]", match[1])
        if carry is None or "&" in match[2]:
            continue
        state, addition, bit_text = carry.groups()
        bit = int(bit_text)
        first = f"{state}_lo[{bit}]" if addition == "a" else f"{state}_is[{(bit - 17) % 64}]"
        second = f"{state}_hi[{bit}]" if addition == "a" else f"{state}_lo[{bit}]"
        previous = f"{state}_c{addition}[{bit - 1}]" if bit else "0"
        expected = f"({first} & {second}) | ({first} & {previous}) | ({second} & {previous})"
        dependencies = re.findall(r"S[12]_(?:lo|hi|ca|is|cb)\[\d+\]", match[1] + expected)
        ready = max(positions[name] for name in dependencies)
        checks.append((ready, match[1], expected))
    if len(checks) != 40:
        raise SystemExit("unexpected inverse circuit carry constraints")
    insertions = {}
    for number, (ready, carry, expected) in enumerate(sorted(checks), 1):
        check = f"\n\tsurviving &= ~(({carry}) ^ ({expected}));"
        if number >= 8 and number % 4 == 0:
            check += "\n\tif (stdx::reduce(surviving, std::bit_or<>{}) == 0) return false;"
        insertions.setdefault(ready, []).append(check)
    for index in reversed(range(len(assignments))):
        if index in insertions:
            position = assignments[index].end()
            source = source[:position] + "".join(insertions[index]) + source[position:]
    position = assignments[0].start()
    source = source[:position] + "\n\tsimd surviving = ~uint64_t{};\n" + source[position:]

output.parent.mkdir(parents=True, exist_ok=True)
if not output.exists() or output.read_text() != source:
    output.write_text(source)
