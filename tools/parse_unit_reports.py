import glob, io, sys, xml.etree.ElementTree as ET

tot = fail = err = skip = 0
files = glob.glob('app/build/test-results/testDebugUnitTest/*.xml')
bad = []
for f in files:
    r = ET.parse(f).getroot()
    tot += int(r.get('tests'))
    fail += int(r.get('failures'))
    err += int(r.get('errors'))
    skip += int(r.get('skipped'))
    for tc in r.iter('testcase'):
        for kind in ('failure', 'error'):
            x = tc.find(kind)
            if x is not None:
                bad.append((kind, tc.get('classname'), tc.get('name'), (x.get('message') or '')[:300]))

out = io.open('build/unit-summary.txt', 'w', encoding='utf-8')
out.write("xml files: %d\ntests: %d failures: %d errors: %d skipped: %d\n" % (len(files), tot, fail, err, skip))
for b in bad[:20]:
    out.write("BAD %s %s :: %s -- %s\n" % b)
out.close()
print("conclusions=%d" % (1 + len(bad)))
