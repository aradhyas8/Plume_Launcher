#!/usr/bin/env python3
"""Checks the translations against English.

  tools/lang.py check [--strict] [--table]

For every app/src/main/res/values-<code>/strings.xml it lists the keys of values/strings.xml that the translation lacks
(the English text shows there), the keys it has that English does not (probably a typo or a removed string), and
the texts whose %s / %d / %1$s markers differ from English. A plural that a translation lacks completely is listed as
missing; each of its forms is checked against English "other" (a form may leave its number out, not add a marker). --strict exits with 1 on unknown keys and wrong markers;
missing keys never fail the check, they are only listed. --table prints a Markdown table, for a CI job summary.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res')
MARKER = re.compile(r'%(?:\d+\$)?[sdfx]')


def read(path):
    """key -> raw text for the strings and plurals of one strings.xml; plurals as name:quantity."""
    out = {}
    root = ET.parse(path).getroot()
    for e in root.findall('string'):
        if e.get('translatable') != 'false':
            out[e.get('name')] = ''.join(e.itertext())
    for p in root.findall('plurals'):
        for i in p.findall('item'):
            out[p.get('name') + ':' + i.get('quantity')] = ''.join(i.itertext())
    return out


def wrong_markers(key, text, en):
    """A string must have the markers of its English text. A plural form, whatever its quantity (zero, two, few, many…),
    is held to English's "other" of the same plural: it may leave the number out ("one photo"), but must not add a marker."""
    if ':' in key:
        other = en.get(key.split(':')[0] + ':other')
        return other is not None and bool(set(MARKER.findall(text)) - set(MARKER.findall(other)))
    return key in en and sorted(MARKER.findall(text)) != sorted(MARKER.findall(en[key]))


def check(strict, table):
    en = read(os.path.join(RES, 'values', 'strings.xml'))
    # A language may use other plural quantities than English: only the strings are required.
    required = [k for k in en if ':' not in k]
    plural_names = sorted({k.split(':')[0] for k in en if ':' in k})
    rows, bad = [], 0
    for path in sorted(glob.glob(os.path.join(RES, 'values-*', 'strings.xml'))):
        code = os.path.basename(os.path.dirname(path))[len('values-'):]
        tr = read(path)
        # A plural counts as missing when the translation has none of its forms (English has one and other; a language
        # may have more, or other quantities).
        missing = [k for k in required if k not in tr] + [n for n in plural_names if not any(k.startswith(n + ':') for k in tr)]
        unknown = [k for k in tr if k not in en and k.split(':')[0] not in plural_names]
        markers = [k for k in tr if wrong_markers(k, tr[k], en)]
        rows.append((code, len(tr), missing, unknown, markers))
        if unknown or markers:
            bad += 1
    if table:
        print('| Language | Keys | Missing | Unknown | Wrong markers |')
        print('|---|---|---|---|---|')
        for code, n, missing, unknown, markers in rows:
            cell = lambda ks: ', '.join(f'`{k}`' for k in ks) or '-'
            print(f'| {code} | {n} | {cell(missing)} | {cell(unknown)} | {cell(markers)} |')
    else:
        for code, n, missing, unknown, markers in rows:
            print(f'values-{code}: {n} keys, {len(missing)} missing, {len(unknown)} unknown, {len(markers)} with other markers')
            for label, ks in (('missing', missing), ('unknown', unknown), ('markers', markers)):
                if ks:
                    print(f'   {label}: ' + ', '.join(ks))
    if strict and bad:
        sys.exit(1)


if __name__ == '__main__':
    args = sys.argv[1:]
    if args[:1] == ['check']:
        check('--strict' in args, '--table' in args)
    else:
        print(__doc__)
        sys.exit(2)
