"""Score the Android ASCEND run with the desktop's own metric.

`units/distance/exact` are imported from the desktop project instead of reimplemented,
so "same test, same sample size" also means "same scoring code". Latency is reported
separately because the desktop evaluator deliberately does not measure it.

    python tools/score_ascend_eval.py --runs .android/ascend-eval-20261025/out-accuracy-*.json
"""
import argparse
import glob
import json
from collections import defaultdict
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def quantile(values, q):
    values = sorted(v for v in values if v is not None and v >= 0)
    return values[min(len(values) - 1, int((len(values) - 1) * q))] if values else None


def summarise(rows, text_of):
    groups = defaultdict(list)
    for row in rows:
        groups[row['category']].append(row)
    out = {}
    for key, group in sorted(groups.items()) + [('TOTAL', rows)]:
        errors = sum(distance(units(r['expected']), units(text_of(r))) for r in group)
        total = sum(len(units(r['expected'])) for r in group)
        out[key] = {
            'n': len(group),
            'exact': sum(exact(r['expected'], text_of(r)) for r in group),
            'unitErrorRate': round(errors / max(1, total), 4),
            'failures': sum(1 for r in group if r.get('error')),
        }
    return out


def latency(rows):
    firsts = [r['firstTextMs'] for r in rows]
    releases = [r['releaseMs'] for r in rows]
    return {
        'cases': len(rows),
        'noText': sum(1 for f in firsts if f is None or f < 0),
        'firstP50': quantile(firsts, .5), 'firstP95': quantile(firsts, .95),
        'firstMax': max(firsts) if firsts else None,
        'releaseP50': quantile(releases, .5), 'releaseP95': quantile(releases, .95),
        'releaseMax': max(releases) if releases else None,
        'wallMsTotal': sum(r['wallMs'] for r in rows),
        'cpuMsTotal': sum(r['cpuMs'] for r in rows),
    }


def load(paths):
    rows = []
    for pattern in paths:
        for name in glob.glob(pattern):
            report = json.loads(Path(name).read_text(encoding='utf-8'))
            rows.extend(report['rows'])
    seen = {}
    for row in rows:
        seen.setdefault(row['id'], row)
    return list(seen.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', nargs='+', required=True, help='out-*.json glob(s)')
    parser.add_argument('--desktop', type=Path, default=Path('D:/Projects/VoxFix Desktop'))
    parser.add_argument('--desktop-run', type=Path,
                        default=Path('D:/Projects/VoxFix Desktop/work/audit-20261025-ascend-r5.json'))
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()

    sys.path.insert(0, str(args.desktop / 'tools'))
    global units, distance, exact
    from p0_score import units, distance, exact  # noqa: E402  (same file as the desktop run)

    rows = load(args.runs)
    phone = summarise(rows, lambda r: r['finalText'])
    result = {'cases': len(rows), 'phone': phone, 'latency': latency(rows)}

    if args.desktop_run.exists():
        desktop = {r['id']: r for r in json.loads(args.desktop_run.read_text(encoding='utf-8'))['rows']}
        paired = [r for r in rows if r['id'] in desktop]
        result['desktop'] = summarise(paired, lambda r: desktop[r['id']]['text'])
        regressed, recovered, bothWrong = [], [], []
        for row in paired:
            was = exact(row['expected'], desktop[row['id']]['text'])
            now = exact(row['expected'], row['finalText'])
            if was and not now:
                regressed.append({'id': row['id'], 'expected': row['expected'],
                                  'desktop': desktop[row['id']]['text'], 'phone': row['finalText']})
            elif now and not was:
                recovered.append({'id': row['id'], 'expected': row['expected'],
                                  'desktop': desktop[row['id']]['text'], 'phone': row['finalText']})
            elif not now:
                bothWrong.append(row['id'])
        result['compare'] = {'paired': len(paired), 'regressed': len(regressed),
                             'recovered': len(recovered), 'bothWrong': len(bothWrong),
                             'eitherWrong': len({r['id'] for r in regressed} | set(bothWrong))}
        result['regressions'] = regressed[:40]

    result['failures'] = [{'id': r['id'], 'error': r['error']} for r in rows if r.get('error')][:20]
    print(json.dumps({k: v for k, v in result.items() if k != 'regressions'}, ensure_ascii=False, indent=2))
    if 'regressions' in result:
        print('\n=== phone wrong, desktop right (first 40) ===')
        for row in result['regressions']:
            print(f"  {row['id']:<34} exp={row['expected']!r} desktop={row['desktop']!r} phone={row['phone']!r}")
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
