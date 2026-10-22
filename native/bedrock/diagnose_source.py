from pathlib import Path
import sys


source = Path(sys.argv[1]).read_text()
output = Path(sys.argv[2])
target = "\tif(sol1.size()) constrain(sol1, O0, O1);"
diagnostic = """
\tuint64_t knownI0 = 0, knownI1 = 0;
\tif (const char* expected = std::getenv("SEEDY_TEST_SEED")) {
\t\tauto mix = [](uint64_t value) {
\t\t\tvalue = (value ^ value >> 30) * 0xbf58476d1ce4e5b9ull;
\t\t\tvalue = (value ^ value >> 27) * 0x94d049bb133111ebull;
\t\t\treturn value ^ value >> 31;
\t\t};
\t\tauto next = [](uint64_t low, uint64_t high) { return std::rotl(low + high, 17) + low; };
\t\tauto step = [](uint64_t low, uint64_t high) {
\t\t\tuint64_t difference = low ^ high;
\t\t\treturn std::pair<uint64_t, uint64_t>{std::rotl(low, 49) ^ difference ^ difference << 21, std::rotl(difference, 28)};
\t\t};
\t\tuint64_t seed = std::strtoull(expected, nullptr, 10) ^ 7640891576956012809ull;
\t\tuint64_t low = mix(seed), high = mix(seed + 0x9e3779b97f4a7c15ull);
\t\tuint64_t actualI0 = next(low, high);
\t\tstd::tie(low, high) = step(low, high);
\t\tuint64_t actualI1 = next(low, high);
\t\tknownI0 = actualI0;
\t\tknownI1 = actualI1;
\t\tauto probe = [&](const char* name, auto& candidates, z3::expr first, z3::expr second) {
\t\t\tif (candidates.empty()) {
\t\t\t\tstd::printf("stage %s empty\\n", name);
\t\t\t\treturn;
\t\t\t}
\t\t\tsolver.push();
\t\t\tconstrain(candidates, first, second);
\t\t\tsolver.add(I0 == ctx.bv_val(actualI0, 64u));
\t\t\tsolver.add(I1 == ctx.bv_val(actualI1, 64u));
\t\t\tstd::printf("stage %s contains_true_state %d candidates %zu\\n", name, solver.check() == z3::sat, candidates.size() - 1);
\t\t\tsolver.pop();
\t\t};
\t\tprobe("bedrock", sol1, O0, O1);
\t\tprobe("deepslate", sol2, O2, O3);
\t\tprobe("vein", sol3, O4, O5);
\t\tprobe("ore", sol4, O6, O7);
\t\tprobe("raw", sol5, O8, O9);
\t}
"""

enumeration_target = "\tcrackFP(i0, i1);"
enumeration = """
\tif (std::getenv("SEEDY_TEST_ENUMERATE")) {
\t\tfor (int index = 1; index <= 64; ++index) {
\t\t\tstd::printf("model %d matches_true_state %d\\n", index, i0 == knownI0 && i1 == knownI1);
\t\t\tif (i0 == knownI0 && i1 == knownI1) return;
\t\t\tsolver.add(I0 != ctx.bv_val(i0, 64u) || I1 != ctx.bv_val(i1, 64u));
\t\t\tif (solver.check() != z3::sat) {
\t\t\t\tstd::printf("no_more_models\\n");
\t\t\t\treturn;
\t\t\t}
\t\t\tz3::model nextModel = solver.get_model();
\t\t\ti0 = nextModel.eval(I0).get_numeral_uint64();
\t\t\ti1 = nextModel.eval(I1).get_numeral_uint64();
\t\t}
\t\treturn;
\t}
"""

if source.count(target) != 1 or source.count(enumeration_target) != 1:
    raise SystemExit("expected one constraint insertion point")
output.write_text(source.replace(target, diagnostic + target, 1).replace(enumeration_target, enumeration + enumeration_target, 1))
