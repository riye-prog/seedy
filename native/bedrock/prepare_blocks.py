from pathlib import Path
import sys


source = Path(sys.argv[1]).read_text()
output = Path(sys.argv[2])

mode_assertion = "assert(1 <= mode && mode <= 31);"
mode_selection = "bool cautious = (mode & 32) != 0;\n\tmode &= 31;\n\tassert(1 <= mode && mode <= 31);"
old_vein = "xvs3.push_back({xv, 0.7, vein});"
new_vein = "if (!cautious || vein) xvs3.push_back({xv, 0.7, vein});"
old_filler = "xvs4.push_back({xv, 0.1, false});"
new_filler = "if (!cautious) xvs4.push_back({xv, 0.1, false});"
old_vein_loop = "\tfor(auto[x,y,z] : maybe_veins) {"
new_vein_loop = old_vein_loop + "\n\t\tif ((y < -60 || y > -8) && (y < 0 || y > 50)) continue;"
old_solver = "\tz3::solver solver(ctx);"
old_first_solve = "\tauto sol1 = solve(xvs1, 1);"
new_first_solve = """\tstd::vector<uint64_t> rawPositions;
\tif (mode & 16) for (auto [coordinate, type] : blocks) {
\t\tif (type != RAW) continue;
\t\tauto [x, y, z] = coordinate;
\t\trawPositions.push_back(getSeed(x, y, z));
\t}
\tstd::sort(rawPositions.begin(), rawPositions.end());
\trawPositions.erase(std::unique(rawPositions.begin(), rawPositions.end()), rawPositions.end());
\tbool useRawConstraints = cautious && !rawPositions.empty();
\tauto sol1 = solve(xvs1, 1);"""
old_raw_constraint = "\tif(sol5.size()) constrain(sol5, O8, O9);"
new_raw_constraint = """\tconst std::array<z3::expr, 6> rawStates{O4, O5, O6, O7, O8, O9};
\tsize_t rawChecks = std::min<size_t>(32, rawPositions.size());
\tstd::vector<uint64_t> rawRefinements;
\tstd::vector<std::tuple<unsigned int, uint64_t, uint32_t, bool>> baseRefinements;
\tstd::vector<std::pair<uint64_t, uint64_t>> rejectedStates;
\tunsigned int relaxedGroups = 0;
\tSolverTimeBudget solverBudget(std::chrono::minutes(3));
\tsendUpdate("commands.bedrockcrack.rawEvidence", false, static_cast<uint64_t>(rawPositions.size()), static_cast<uint64_t>(rawChecks));
\tauto configureSolver = [&] {
\t\tsolver.reset();
\t\tif (sol1.size()) constrain(sol1, O0, O1);
\t\tif (sol2.size()) constrain(sol2, O2, O3);
\t\tif (relaxedGroups < 2 && sol3.size()) constrain(sol3, O4, O5);
\t\tif (relaxedGroups < 1 && sol4.size()) constrain(sol4, O6, O7);
\t\tfor (size_t index = 0; index < rawChecks; index++)
\t\t\tsolver.add(rawOreConstraint(rawStates, rawPositions[index * rawPositions.size() / rawChecks]));
\t\tfor (uint64_t position : rawRefinements) solver.add(rawOreConstraint(rawStates, position));
\t\tfor (auto [group, position, cutoff, expected] : baseRefinements)
\t\t\tsolver.add(positionalChanceConstraint(group == 0 ? O0 : O2, group == 0 ? O1 : O3, position, cutoff, expected));
\t\tfor (auto [low, high] : rejectedStates) solver.add(I0 != ctx.bv_val(low, 64u) || I1 != ctx.bv_val(high, 64u));
\t};
\tconfigureSolver();
\tauto checkSolver = [&] {
\t\twhile (shouldContinueSearch()) {
\t\t\tif (solverBudget.expired()) {
\t\t\t\tsendUpdate("commands.bedrockcrack.solverBudgetExceeded", false);
\t\t\t\treturn z3::unknown;
\t\t\t}
\t\t\tsolver.set("timeout", solverBudget.timeoutMillis(useRawConstraints && relaxedGroups < 2 ? 60000u : 180000u));
\t\t\tauto result = cancellableSolverCheck(solver, shouldContinueSearch);
\t\t\tbool timedOut = result == z3::unknown && (solver.reason_unknown() == "timeout" || solver.reason_unknown() == "canceled");
\t\t\tif (timedOut && shouldContinueSearch() && (solverBudget.expired() || !useRawConstraints || relaxedGroups == 2)) {
\t\t\t\tsendUpdate("commands.bedrockcrack.solverBudgetExceeded", false);
\t\t\t\treturn result;
\t\t\t}
\t\t\tif (!useRawConstraints || relaxedGroups == 2 || (result != z3::unsat && !timedOut)) return result;
\t\t\tif (!shouldContinueSearch()) return z3::unknown;
\t\t\trelaxedGroups++;
\t\t\tsendUpdate(timedOut ? "commands.bedrockcrack.solverTimeLimit" : "commands.bedrockcrack.relaxingOreEvidence", false, static_cast<uint64_t>(relaxedGroups));
\t\t\tconfigureSolver();
\t\t}
\t\treturn z3::unknown;
\t};"""
old_return = "\tfloat r = (out >> 40) * 0x1.0p-24;\n\treturn double(r) < p;"
new_return = "\treturn (out >> 40) < cutoff;"
old_empty = "\tif(xvs.size() == 0)\n\t\treturn {};"
new_empty = """\tif(xvs.size() == 0)
\t\treturn {};
\tstd::vector<std::tuple<uint64_t, uint32_t, bool>> packed;
\tpacked.reserve(xvs.size());
\tfor (auto [xv, probability, expected] : xvs)
\t\tif (mode != 2 || expected) packed.emplace_back(xv, static_cast<uint32_t>(std::ceil(probability * 16777216.0)), expected);"""
old_scan = "\tsendUpdate(\"commands.bedrockcrack.crackedRandomStateRandom\", false, i0, i1);\n\tcrackFP(i0, i1);"
new_scan = """\tauto randomLong = [](uint64_t low, uint64_t high) {
\t\treturn std::rotl(low + high, 17) + low;
\t};
\tauto advance = [](uint64_t& low, uint64_t& high) {
\t\tuint64_t difference = low ^ high;
\t\tlow = std::rotl(low, 49) ^ difference ^ difference << 21;
\t\thigh = std::rotl(difference, 28);
\t};
\tstd::vector<uint64_t> rawMismatches;
\tstd::vector<std::tuple<unsigned int, uint64_t, uint32_t, bool>> baseMismatches;
\tauto validate = [&](uint64_t modelLow, uint64_t modelHigh) {
\t\tbaseMismatches.clear();
\t\tauto countErrors = [&](const auto& observations, uint64_t saltLow, uint64_t saltHigh, unsigned int group) {
\t\t\tuint64_t low = modelLow ^ saltLow;
\t\t\tuint64_t high = modelHigh ^ saltHigh;
\t\t\tuint64_t first = randomLong(low, high);
\t\t\tadvance(low, high);
\t\t\tuint64_t second = randomLong(low, high);
\t\t\tsize_t stride = group < 2 ? 1 : std::max<size_t>(1, observations.size() / 4096);
\t\t\tsize_t errors = 0, checked = 0;
\t\t\tsize_t refinements = 0;
\t\t\tfor (size_t index = 0; index < observations.size(); index += stride) {
\t\t\t\tauto [position, probability, expected] = observations[index];
\t\t\t\tif (group == 1 && !expected) continue;
\t\t\t\tuint32_t cutoff = static_cast<uint32_t>(std::ceil(probability * 16777216.0));
\t\t\t\tbool mismatch = get<0>(first, second, position, cutoff) != expected;
\t\t\t\terrors += mismatch;
\t\t\t\tif (mismatch && group < 2 && refinements++ < 16) baseMismatches.emplace_back(group, position, cutoff, expected);
\t\t\t\tchecked++;
\t\t\t}
\t\t\treturn std::pair{errors, checked};
\t\t};
\t\tauto [bedrockErrors, bedrockChecked] = countErrors(xvs1, 13544455532117611141ull, 14185350335435586452ull, 0);
\t\tauto [deepslateErrors, deepslateChecked] = countErrors(xvs2, 10411719568726253007ull, 14964796469053385315ull, 1);
\t\tauto [veinErrors, veinChecked] = countErrors(xvs3, 11207227798492025197ull, 3091299299654006625ull, 2);
\t\tsize_t errors = bedrockErrors + deepslateErrors;
\t\tsize_t checked = bedrockChecked + deepslateChecked;
\t\tuint64_t oreLow = modelLow ^ 11207227798492025197ull;
\t\tuint64_t oreHigh = modelHigh ^ 3091299299654006625ull;
\t\tuint64_t forkLow = randomLong(oreLow, oreHigh);
\t\tadvance(oreLow, oreHigh);
\t\tuint64_t forkHigh = randomLong(oreLow, oreHigh);
\t\tstd::array<uint64_t, 6> numericRawStates{forkLow, forkHigh};
\t\tfor (size_t call = 1; call < 3; call++) {
\t\t\tadvance(forkLow, forkHigh);
\t\t\tnumericRawStates[call * 2] = forkLow;
\t\t\tnumericRawStates[call * 2 + 1] = forkHigh;
\t\t}
\t\trawMismatches.clear();
\t\tfor (uint64_t position : rawPositions) if (!rawOreMatches(numericRawStates, position)) rawMismatches.push_back(position);
\t\treturn std::tuple{errors, checked, veinErrors, veinChecked};
\t};
\tuint64_t candidateLimit = useRawConstraints ? 256 : 8;
\tfor (uint64_t index = 0; index < candidateLimit && shouldContinueSearch(); index++) {
\t\tsendUpdate("commands.bedrockcrack.validatingRandomState", false);
\t\tauto [errors, checked, veinErrors, veinChecked] = validate(i0, i1);
\t\tbool baseMatches = errors == 0;
\t\tbool veinMatches = useRawConstraints || veinChecked < 256 || veinErrors * 50 <= veinChecked * 9;
\t\tbool blockMatches = baseMatches && veinMatches && rawMismatches.empty();
\t\tif (blockMatches && matchesRandomState(i0, i1)) {
\t\t\tsendUpdate("commands.bedrockcrack.crackedRandomStateRandom", false, i0, i1);
\t\t\tcrackFP(i0, i1);
\t\t\treturn;
\t\t}
\t\tif (blockMatches) sendUpdate("commands.bedrockcrack.oreNoiseMismatch", false);
\t\tsendUpdate("commands.bedrockcrack.stateCandidateRejected", false, index + 1, static_cast<uint64_t>(veinErrors), static_cast<uint64_t>(veinChecked));
\t\tsendUpdate("commands.bedrockcrack.baseEvidenceMismatch", false, static_cast<uint64_t>(errors), static_cast<uint64_t>(checked));
\t\tif (index + 1 == candidateLimit) break;
\t\tfor (auto refinement : baseMismatches) {
\t\t\tbaseRefinements.push_back(refinement);
\t\t\tauto [group, position, cutoff, expected] = refinement;
\t\t\tsolver.add(positionalChanceConstraint(group == 0 ? O0 : O2, group == 0 ? O1 : O3, position, cutoff, expected));
\t\t}
\t\tsize_t refinements = std::min<size_t>(16, rawMismatches.size());
\t\tfor (size_t added = 0; added < refinements; added++)
\t\t{
\t\t\tuint64_t position = rawMismatches[added * rawMismatches.size() / refinements];
\t\t\trawRefinements.push_back(position);
\t\t\tsolver.add(rawOreConstraint(rawStates, position));
\t\t}
\t\trejectedStates.emplace_back(i0, i1);
\t\tsolver.add(I0 != ctx.bv_val(i0, 64u) || I1 != ctx.bv_val(i1, 64u));
\t\tsendUpdate("commands.bedrockcrack.startingZ3Search", false);
\t\tauto nextResult = checkSolver();
\t\tif (nextResult != z3::sat) {
\t\t\tsendUpdate(nextResult == z3::unsat ? "commands.bedrockcrack.stateValidationFailed" : "commands.bedrockcrack.Z3Failed", false);
\t\t\treturn;
\t\t}
\t\tz3::model nextModel = solver.get_model();
\t\ti0 = nextModel.eval(I0).get_numeral_uint64();
\t\ti1 = nextModel.eval(I1).get_numeral_uint64();
\t}
\tsendUpdate("commands.bedrockcrack.stateValidationFailed", false);"""

expected_counts = {
    "#include <algorithm>": 1,
    "static bool get(uint64_t seedlo, uint64_t seedhi, uint64_t xv, double p)": 1,
    "static std::vector<std::bitset<128>> solve(std::vector<std::tuple<uint64_t, double, bool>> xvs, int mode)": 1,
    mode_assertion: 1,
    old_vein: 1,
    old_filler: 1,
    old_vein_loop: 1,
    old_solver: 1,
    old_first_solve: 1,
    old_raw_constraint: 1,
    "\tauto sol3 = solve(xvs3, 3);": 1,
    "\tauto sol4 = solve(xvs4, 4);": 1,
    "\tauto sol5 = solve(xvs5, 5);": 1,
    "\tif(sol1.size()) constrain(sol1, O0, O1);": 1,
    "\tif(sol2.size()) constrain(sol2, O2, O3);": 1,
    "\tif(sol3.size()) constrain(sol3, O4, O5);": 1,
    "\tif(sol4.size()) constrain(sol4, O6, O7);": 1,
    "\tz3::check_result res = solver.check();": 1,
    "\tfor(; iters < 21; iters++)\n\t{": 1,
    old_return: 1,
    old_empty: 1,
    old_scan: 1,
    "for(auto[xv,p,ret] : xvs)": 3,
    "xv, p) != ret": 3,
}
if any(source.count(value) != count for value, count in expected_counts.items()):
    raise SystemExit("unexpected bedrock solver source")

source = source.replace("#include <algorithm>", '#include <algorithm>\n#include <cmath>\n#include "raw_constraints.h"\n#include "solver_control.h"\n#include "ore_validation.h"', 1)
source = source.replace(old_solver, "\tauto solver = makeRecoverySolver(ctx);", 1)
constraint_start = source.index("\tauto constrain = [&]")
constraint_end = source.index("\n\t};", constraint_start) + len("\n\t};")
source = source[:constraint_start] + "\tauto constrain = [&](std::span<const std::bitset<128>> candidates, z3::expr low, z3::expr high) { constrainStateCandidates(solver, candidates, low, high); };" + source[constraint_end:]
source = source.replace('#include "ore_validation.h"', '#include "ore_validation.h"\n#include "candidate_constraints.h"', 1)
source = source.replace('#include "candidate_constraints.h"', '#include "candidate_constraints.h"\n#include "positional_candidates.h"', 1)
positional_refinement = """\tif (mode <= 2 && iters >= 17) {
\t\tstd::vector<std::pair<uint64_t, uint64_t>> candidates;
\t\tfor (const auto& candidate : cur)
\t\t\tcandidates.emplace_back(candidate.w1 << (64 - iters) | candidate.w2 << (47 - iters), candidate.w3 << (47 - iters));
\t\tauto refined = refinePositionalCandidates(packed, candidates, static_cast<unsigned int>(iters), shouldContinueSearch);
\t\tif (!refined.empty()) sendUpdate("commands.bedrockcrack.foundationCandidates", false, static_cast<uint64_t>(mode), static_cast<uint64_t>(refined[0].count()), static_cast<uint64_t>(refined.size() - 1));
\t\treturn refined;
\t}
"""
source = source.replace("\tstd::vector<std::bitset<128>> ret;", positional_refinement + "\tstd::vector<std::bitset<128>> ret;", 1)
source = source.replace("static bool get(uint64_t seedlo, uint64_t seedhi, uint64_t xv, double p)", "static bool get(uint64_t seedlo, uint64_t seedhi, uint64_t xv, uint32_t cutoff)", 1)
source = source.replace("static std::vector<std::bitset<128>> solve(std::vector<std::tuple<uint64_t, double, bool>> xvs, int mode)", "static std::vector<std::bitset<128>> solve(const std::vector<std::tuple<uint64_t, double, bool>>& xvs, int mode)", 1)
source = source.replace(old_return, new_return, 1)
source = source.replace(old_empty, new_empty, 1)
source = source.replace("for(auto[xv,p,ret] : xvs)", "for(auto[xv,cutoff,ret] : packed)")
source = source.replace("xv, p) != ret", "xv, cutoff) != ret")
source = source.replace(mode_assertion, mode_selection, 1)
source = source.replace(old_vein, new_vein, 1)
source = source.replace(old_filler, new_filler, 1)
source = source.replace(old_vein_loop, new_vein_loop, 1)
source = source.replace(old_first_solve, new_first_solve, 1)
source = source.replace("\tauto sol5 = solve(xvs5, 5);", "", 1)
for number, first, second in ((1, 0, 1), (2, 2, 3), (3, 4, 5), (4, 6, 7)):
    source = source.replace(f"\tif(sol{number}.size()) constrain(sol{number}, O{first}, O{second});", "", 1)
source = source.replace(old_raw_constraint, new_raw_constraint, 1)
source = source.replace("\tz3::check_result res = solver.check();", "\tz3::check_result res = checkSolver();", 1)
source = source.replace("\tfor(; iters < 21; iters++)\n\t{", "\tfor(; iters < 21; iters++)\n\t{\n\t\tif (!shouldContinueSearch()) return {};", 1)
for number in range(1, 6):
    event = f'\tsendUpdate("commands.bedrockcrack.completedBitSearch{number}", false);'
    if source.count(event) != 1:
        raise SystemExit("unexpected bit search event")
    source = source.replace(event, event + "\n\tif (!shouldContinueSearch()) return;", 1)
source = source.replace(old_scan, new_scan, 1)

source = source.replace('#include "positional_candidates.h"', '#include "positional_candidates.h"\n#include "cancellation.h"', 1)
for old, new in (
    ("void bedrockseed$crack(int size, block_entry* entries, int mode)\n{", "void bedrockseed$crack(int size, block_entry* entries, int mode)\n{\n\tRecoveryCancellationSession cancellation;\n\tif (!shouldContinueSearch()) return;"),
    ("\tfor(uint64_t entry_idx = 0; entry_idx < size; ++entry_idx) {", "\tfor(uint64_t entry_idx = 0; entry_idx < size; ++entry_idx) {\n\t\tif ((entry_idx & 4095) == 0 && !shouldContinueSearch()) return;"),
    ("\tfor(auto[coord, typ] : blocks) {", "\tsize_t classificationIndex = 0;\n\tfor(auto[coord, typ] : blocks) {\n\t\tif ((classificationIndex++ & 4095) == 0 && !shouldContinueSearch()) return;"),
    ("\tfor(auto[x,y,z] : maybe_veins) {", "\tfor(auto[x,y,z] : maybe_veins) {\n\t\tif (!shouldContinueSearch()) return;"),
    ("\t\tfor(beam_entry& to : next) {", "\t\tfor(beam_entry& to : next) {\n\t\t\tif (!shouldContinueSearch()) continue;"),
    ("\t\tstd::ranges::stable_sort(next.begin()", "\t\tif (!shouldContinueSearch()) return {};\n\t\tstd::ranges::stable_sort(next.begin()"),
):
    if source.count(old) != 1:
        raise SystemExit("unexpected cancellation anchor: " + old)
    source = source.replace(old, new, 1)

output.parent.mkdir(parents=True, exist_ok=True)
if not output.exists() or output.read_text() != source:
    output.write_text(source)
