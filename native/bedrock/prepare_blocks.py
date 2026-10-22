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

expected_counts = {
    "#include <algorithm>": 1,
    "static bool get(uint64_t seedlo, uint64_t seedhi, uint64_t xv, double p)": 1,
    "static std::vector<std::bitset<128>> solve(std::vector<std::tuple<uint64_t, double, bool>> xvs, int mode)": 1,
    mode_assertion: 1,
    old_vein: 1,
    old_filler: 1,
    old_return: 1,
    old_empty: 1,
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

output.parent.mkdir(parents=True, exist_ok=True)
if not output.exists() or output.read_text() != source:
    output.write_text(source)
