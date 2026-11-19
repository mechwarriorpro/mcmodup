import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch

from mcmodup.inputs import InputError
from mcmodup.minechem_final_synthesis import TRACE, asset_paths, observations, resolve


class FinalSynthesisTraceTests(unittest.TestCase):
    def test_original_registration_order_and_boundaries(self):
        rules = observations()
        self.assertEqual(Counter(row.canonical.split(':')[0] for row in rules), {'paid': 278, 'shadow': 22, 'power': 1})
        by_key = {row.key: row for row in rules}
        stone = by_key['v1:0']
        self.assertEqual((stone.count, stone.energy, stone.shaped), (7, 500, True))
        self.assertEqual(stone.pattern, (('e13', 1), None, None, ('e7', 2), None, None))
        self.assertEqual(by_key['v265:0'].pattern, (('e25', 16),))
        self.assertFalse(by_key['v265:0'].shaped)
        self.assertEqual(by_key['v42:0'].pattern, (('e25', 144),))
        self.assertEqual(by_key['v133:0'].energy, 1_500_000)
        shadow = by_key['v152:0']
        self.assertEqual(rules[int(shadow.canonical.split(':')[1])].key, 'v331:0')
        self.assertLess(rules.index(by_key['v4360:0']), rules.index(by_key['v264:0']))

    def test_mapped_outputs_and_asset_paths_are_isolated(self):
        namespace = 'minechem_chemistry'
        ids = {'m1': f'{namespace}:molecule_water'}
        self.assertEqual(resolve('m1', namespace, ids), (ids['m1'], True))
        self.assertEqual(resolve('v4360:0', namespace, ids), (f'{namespace}:polytool', True))
        self.assertEqual(resolve('v351:15', namespace, ids), ('minecraft:bone_meal', True))
        self.assertEqual(resolve('v6:1', namespace, ids), ('minecraft:spruce_sapling', True))
        with self.assertRaisesRegex(InputError, 'No verified FINAL vanilla output'):
            resolve('v31:2', namespace, ids)
        self.assertEqual(set(asset_paths(namespace).values()), {
            f'assets/{namespace}/textures/gui/final_synthesis.png', f'assets/{namespace}/textures/block/synthesizer.png',
            f'assets/{namespace}/textures/item/atomic_manipulator.png', f'assets/{namespace}/textures/item/polytool.png',
        })

    def test_bad_trace_rejected_before_build(self):
        original = TRACE.read_text(encoding='utf-8')
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'trace.txt'
            with patch('mcmodup.minechem_final_synthesis.TRACE', path):
                for bad in (original.replace('shadow:19', 'shadow:200', 1), original.replace('e25x144', 'e25x999'), original + original):
                    path.write_text(bad, encoding='utf-8')
                    with self.assertRaises(InputError):
                        observations()
