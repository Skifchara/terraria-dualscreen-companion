"""Consistency checks for the companion's text: no missing keys, no half-translated rows, mod messages all covered.

  python tests/test_strings.py
"""
import glob
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "src", "companion", "java", "com", "terraduo", "companion")
MOD = os.path.join(ROOT, "src", "tlpro", "Modified", "1.mod")
CYRILLIC = "[А-яЁё]"


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


STRINGS = read(os.path.join(APP, "Strings.java"))
ROWS = re.findall(r'put\("([^"]+)",\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"\);', STRINGS)


class StringsTest(unittest.TestCase):
    def test_every_put_parses(self):
        calls = len(re.findall(r'^\s*put\("', STRINGS, re.M))
        self.assertEqual(len(ROWS), calls)

    def test_no_empty_translation(self):
        empty = [key for key, *values in ROWS if not all(v.strip() for v in values)]
        self.assertEqual(empty, [])

    def test_format_placeholders_match(self):
        bad = []
        for key, *values in ROWS:
            counts = {len(re.findall(r"%(?:\d+\$)?[sd]", v)) for v in values}
            if len(counts) > 1:
                bad.append(key)
        self.assertEqual(bad, [])

    def test_used_keys_are_defined(self):
        defined = {key for key, *_ in ROWS}
        used = set()
        for path in glob.glob(os.path.join(APP, "*.java")):
            if path.endswith("Strings.java"):
                continue
            used |= set(re.findall(r'Strings\.get\("([^"]+)"\)', read(path)))
        self.assertEqual(sorted(used - defined), [])

    def test_mod_messages_are_translated(self):
        table = set(re.findall(r'add\("([^"]+)"', read(os.path.join(APP, "ModErrors.java"))))
        used = set()
        for path in glob.glob(os.path.join(MOD, "*.js")):
            used |= set(re.findall(r'Error\("([^"]*%s[^"]*)"\)' % CYRILLIC, read(path)))
        self.assertEqual(sorted(used - table), [])


if __name__ == "__main__":
    unittest.main()
