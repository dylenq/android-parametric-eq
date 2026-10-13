#!/usr/bin/env bash
# Static validation of .github/workflows/release.yml (the workflow has never run; this is all we can check).
# Prints PASS/FAIL lines and exits non-zero on any FAIL. Needs ruby (Psych); shellcheck is used if present.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
WF=".github/workflows/release.yml"
FAILS=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
check() { # check <description> <command...>
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$d"; else fail "$d"; fi
}

[ -f "$WF" ] || { echo "FAIL: $WF missing"; exit 1; }

RUBY_OUT="$(ruby -ryaml -e '
require "yaml"
wf = File.read(ARGV[0])
y = YAML.safe_load(wf, aliases: false)
res = []
ok = ->(d, c) { res << ((c ? "PASS: " : "FAIL: ") + d) }
ok.("workflow YAML parses", y.is_a?(Hash))
on = y["on"].nil? ? y[true] : y["on"]
tags = on.is_a?(Hash) && on["push"].is_a?(Hash) ? on["push"]["tags"] : nil
ok.("trigger is push on tags matching v*", tags.is_a?(Array) && tags.include?("v*"))
ok.("trigger has only push (no pull_request_target, no pull_request)", on.is_a?(Hash) && on.keys == ["push"])
ok.("no pull_request_target anywhere in executable keys", !wf.lines.reject { |l| l.strip.start_with?("#") }.join.include?("pull_request_target"))
ok.("top-level permissions is empty", y["permissions"] == {})
jobs = y["jobs"] || {}
writers = jobs.select { |_, j| (j["permissions"] || {})["contents"] == "write" }
ok.("exactly one job grants contents: write", writers.size == 1 && jobs.size == 1)
job = jobs.values.first || {}
ok.("runs-on is ubuntu-24.04", job["runs-on"] == "ubuntu-24.04")
ok.("timeout-minutes is set", job["timeout-minutes"].is_a?(Integer))
conc = y["concurrency"] || {}
ok.("concurrency does not cancel in progress", conc["cancel-in-progress"] == false)
steps = job["steps"] || []
lines = wf.lines
steps.each do |s|
  next unless s["uses"]
  u = s["uses"]
  idx = lines.index { |l| l.include?("uses: " + u) }
  line = idx ? lines[idx] : ""
  sha = u =~ /@[0-9a-f]{40}\z/
  tagc = line =~ /# v\d+\.\d+\.\d+/
  tagpinned = line.include?("tag-pinned because the SHA could not be resolved")
  ok.("uses #{u} pinned to a 40-hex SHA with a # vX.Y.Z comment (or explicit tag-pinned comment)", (sha && tagc) || tagpinned)
end
ok.("at least one uses: step", steps.any? { |s| s["uses"] })
runs = steps.map { |s| s["run"] }.compact
ok.("no secrets. reference inside any run: text", runs.none? { |r| r.include?("secrets.") })
ok.("no github.ref_name interpolated into a run: text", runs.none? { |r| r =~ /\$\{\{\s*github\.ref_name/ })
ok.("no ${{ }} expression of any kind inside run: text", runs.none? { |r| r.include?("${{") })
co = steps.find { |s| s["uses"].to_s.start_with?("actions/checkout@") }
ok.("checkout has persist-credentials: false", co && co["with"] && co["with"]["persist-credentials"] == false)
rel = steps.find { |s| s["uses"].to_s.start_with?("softprops/action-gh-release@") }
ok.("release step has draft: true", rel && rel["with"] && rel["with"]["draft"] == true)
ok.("release step has fail_on_unmatched_files", rel && rel["with"] && rel["with"]["fail_on_unmatched_files"] == true)
clean = steps.select { |s| s["if"].to_s.strip == "always()" && s["run"].to_s.include?("release.jks") && s["run"].to_s.include?("rm ") }
ok.("an if: always() step removes the decoded keystore", clean.size == 1)
ok.("keystore removal is the last step", steps.last && steps.last["if"].to_s.strip == "always()")
ok.("no set -x anywhere", !wf.include?("set -x"))
ok.("secrets appear only under env: (EQ_KEYSTORE_BASE64/PASSWORD/EQ_KEY_ALIAS)", steps.all? { |s| s["run"].nil? || !s["run"].include?("secrets") } && wf.scan(/secrets\.[A-Z0-9_]+/).uniq.sort == %w[secrets.EQ_KEYSTORE_BASE64 secrets.EQ_KEYSTORE_PASSWORD secrets.EQ_KEY_ALIAS].sort)
header = lines.first(8).join.downcase
ok.("header comment says the workflow has never run", header.include?("never run"))
puts res
' "$WF" 2>&1)" || true
echo "$RUBY_OUT"
FAILS=$((FAILS + $(echo "$RUBY_OUT" | grep -c '^FAIL' || true)))
echo "$RUBY_OUT" | grep -q '^PASS' || fail "ruby produced no checks"

for needle in 'ndk;27.1.12297006' '--locked' 'cargo-ndk' 'scripts/release-apk.sh' 'TESTSIGNED'; do
  if grep -qF -- "$needle" "$WF"; then pass "workflow contains $needle"; else fail "workflow contains $needle"; fi
done
check "workflow is not on ubuntu-latest" bash -c "! grep -q 'ubuntu-latest' $WF"

for f in scripts/*.sh; do
  if bash -n "$f" 2>/dev/null; then pass "bash -n $f"; else fail "bash -n $f"; fi
done
if command -v shellcheck >/dev/null 2>&1; then
  for f in scripts/*.sh; do
    if shellcheck -S warning "$f" >/dev/null 2>&1; then pass "shellcheck $f"; else fail "shellcheck $f"; fi
  done
fi

[ "$FAILS" = "0" ] || { echo "$FAILS check(s) failed"; exit 1; }
echo "all workflow checks passed"
