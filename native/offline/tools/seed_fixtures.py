"""Collect real games (seed, variant, deck) from hanab.live to test the seeded shuffle against."""
import json
import sys
import urllib.request

OUT = sys.argv[1]
WANT = [
    'No Variant', '6 Suits', '4 Suits', 'Black (6 Suits)', 'Rainbow (5 Suits)', 'Rainbow (6 Suits)',
    'Pink (5 Suits)', 'Brown (5 Suits)', 'White (5 Suits)', 'Omni (5 Suits)', 'Null (5 Suits)', 'Muddy Rainbow (5 Suits)',
    'Light Pink (5 Suits)', 'Prism (5 Suits)', 'Sudoku (5 Suits)', 'Up or Down (5 Suits)', 'Reversed (5 Suits)',
]


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'hanab-android-seed-check'})
    return json.load(urllib.request.urlopen(req, timeout=60))


names = {int(k): v for k, v in get('https://hanab.live/api/v1/variants').items()}
ids = {v: k for k, v in names.items()}
fixtures = []
seen = set()
for player in ('Libster', 'WillFlame'):
    for page in range(0, 12):
        rows = get(f'https://hanab.live/api/v1/history/{player}?size=100&page={page}')['rows'] or []
        for r in rows:
            name = names.get(r['variant'])
            key = (name, r['num_players'] if name in ('No Variant', '6 Suits') else 0)
            if name not in WANT or key in seen:
                continue
            try:
                ex = get(f"https://hanab.live/export/{r['id']}")
            except Exception as e:  # noqa: BLE001
                print('skip', r['id'], e)
                continue
            seen.add(key)
            fixtures.append({
                'id': r['id'], 'seed': ex['seed'], 'variant': name, 'players': r['num_players'],
                'deck': [[c['suitIndex'], c['rank']] for c in ex['deck']],
            })
            print('got', name, r['num_players'], ex['seed'], len(ex['deck']))
json.dump(fixtures, open(OUT, 'w'), separators=(',', ':'))
print(len(fixtures), 'fixtures')
