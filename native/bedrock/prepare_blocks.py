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
old_return = "\tfloat r = (out >> 40) * 0x1.0p-24;\n\treturn double(r) < p;"
new_return = "\treturn (out >> 40) < cutoff;"
old_empty = "\tif(xvs.size() == 0)\n\t\treturn {};"
new_empty = """\tif(xvs.size() == 0)
\t\treturn {};
\tstd::vector<std::tuple<uint64_t, uint32_t, bool>> packed;
\tpacked.reserve(xvs.size());
\tfor (auto [xv, probability, expected] : xvs)
\t\tpacked.emplace_back(xv, static_cast<uint32_t>(std::ceil(probability * 16777216.0)), expected);"""
old_scan = "\tsendUpdate(\"commands.bedrockcrack.crackedRandomStateRandom\", false, i0, i1);\n\tcrackFP(i0, i1);"
new_scan = """\tauto randomLong = [](uint64_t low, uint64_t high) {
\t\treturn std::rotl(low + high, 17) + low;
\t};
\tauto advance = [](uint64_t& low, uint64_t& high) {
\t\tuint64_t difference = low ^ high;
\t\tlow = std::rotl(low, 49) ^ difference ^ difference << 21;
\t\thigh = std::rotl(difference, 28);
\t};
\tauto validate = [&](uint64_t modelLow, uint64_t modelHigh) {
\t\tauto countErrors = [&](const auto& observations, uint64_t saltLow, uint64_t saltHigh) {
\t\t\tuint64_t low = modelLow ^ saltLow;
\t\t\tuint64_t high = modelHigh ^ saltHigh;
\t\t\tuint64_t first = randomLong(low, high);
\t\t\tadvance(low, high);
\t\t\tuint64_t second = randomLong(low, high);
\t\t\tsize_t stride = std::max<size_t>(1, observations.size() / 4096);
\t\t\tsize_t errors = 0, checked = 0;
\t\t\tfor (size_t index = 0; index < observations.size(); index += stride) {
\t\t\t\tauto [position, probability, expected] = observations[index];
\t\t\t\tuint32_t cutoff = static_cast<uint32_t>(std::ceil(probability * 16777216.0));
\t\t\t\terrors += get<0>(first, second, position, cutoff) != expected;
\t\t\t\tchecked++;
\t\t\t}
\t\t\treturn std::pair{errors, checked};
\t\t};
\t\tauto [bedrockErrors, bedrockChecked] = countErrors(xvs1, 13544455532117611141ull, 14185350335435586452ull);
\t\tauto [deepslateErrors, deepslateChecked] = countErrors(xvs2, 10411719568726253007ull, 14964796469053385315ull);
\t\tauto [veinErrors, veinChecked] = countErrors(xvs3, 11207227798492025197ull, 3091299299654006625ull);
\t\tsize_t errors = bedrockErrors + deepslateErrors;
\t\tsize_t checked = bedrockChecked + deepslateChecked;
\t\treturn std::tuple{errors, checked, veinErrors, veinChecked};
\t};
\tsendUpdate("commands.bedrockcrack.validatingRandomState", false);
\tfor (uint64_t index = 0; index < 3 && shouldContinueSearch(); index++) {
\t\tauto [errors, checked, veinErrors, veinChecked] = validate(i0, i1);
\t\tbool baseMatches = checked < 1024 || errors <= std::max<size_t>(8, checked / 100);
\t\tbool veinMatches = veinChecked < 256 || veinErrors * 50 <= veinChecked * 9;
\t\tif (baseMatches && veinMatches) {
\t\t\tsendUpdate("commands.bedrockcrack.crackedRandomStateRandom", false, i0, i1);
\t\t\tcrackFP(i0, i1);
\t\t\treturn;
\t\t}
\t\tsendUpdate("commands.bedrockcrack.stateCandidateRejected", false, index + 1, static_cast<uint64_t>(veinErrors), static_cast<uint64_t>(veinChecked));
\t\tsolver.add(I0 != ctx.bv_val(i0, 64u) || I1 != ctx.bv_val(i1, 64u));
\t\tauto nextResult = solver.check();
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
    old_return: 1,
    old_empty: 1,
    old_scan: 1,
    "for(auto[xv,p,ret] : xvs)": 3,
    "xv, p) != ret": 3,
}
if any(source.count(value) != count for value, count in expected_counts.items()):
    raise SystemExit("unexpected bedrock solver source")

source = source.replace("#include <algorithm>", "#include <algorithm>\n#include <cmath>", 1)
source = source.replace("static bool get(uint64_t seedlo, uint64_t seedhi, uint64_t xv, double p)", "static bool get(uint64_t seedlo, uint64_t seedhi, uint64_t xv, uint32_t cutoff)", 1)
source = source.replace("static std::vector<std::bitset<128>> solve(std::vector<std::tuple<uint64_t, double, bool>> xvs, int mode)", "static std::vector<std::bitset<128>> solve(const std::vector<std::tuple<uint64_t, double, bool>>& xvs, int mode)", 1)
source = source.replace(old_return, new_return, 1)
source = source.replace(old_empty, new_empty, 1)
source = source.replace("for(auto[xv,p,ret] : xvs)", "for(auto[xv,cutoff,ret] : packed)")
source = source.replace("xv, p) != ret", "xv, cutoff) != ret")
source = source.replace(mode_assertion, mode_selection, 1)
source = source.replace(old_vein, new_vein, 1)
source = source.replace(old_filler, new_filler, 1)
source = source.replace(old_scan, new_scan, 1)

output.parent.mkdir(parents=True, exist_ok=True)
if not output.exists() or output.read_text() != source:
    output.write_text(source)
