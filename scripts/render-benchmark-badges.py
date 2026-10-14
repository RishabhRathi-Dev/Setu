#!/usr/bin/env python3
"""Refresh local badge SVGs and README links from the committed JSON snapshots."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re

spec = importlib.util.spec_from_file_location('badge_helpers', Path(__file__).with_name('update-benchmark-badges.py'))
badges = importlib.util.module_from_spec(spec)
spec.loader.exec_module(badges)

GROUPS = {
    'benchmark-metrics-badges': ('in-memory-sustained', 'file-sustained'),
    'benchmark-historical-badges': ('memory-throughput', 'file-throughput', 'memory-capacity',
                                    'file-capacity', 'memory-jfr', 'file-jfr'),
}


def render(readme, output, workflow_url):
    source = readme.read_text(encoding='utf-8')
    assets = []
    for block, names in GROUPS.items():
        pattern = f'<!-- {block}:start -->.*?<!-- {block}:end -->'
        if len(re.findall(pattern, source, re.S)) != 1:
            raise ValueError(f'missing or duplicate {block} block')
        links = []
        for name in names:
            data = json.loads((output / (name + '.json')).read_text(encoding='utf-8'))
            svg = badges.badge_svg(data['label'], data['message'], data['color'])
            path = output / (name + '.svg')
            assets.append((path, svg))
            relative = Path(os.path.relpath(path, readme.parent)).as_posix()
            links.append(badges.readme_badge(data['label'], data['message'], data['color'], workflow_url, relative))
        source = re.sub(pattern, lambda _: f'<!-- {block}:start -->\n' + '\n'.join(links)
                        + f'\n<!-- {block}:end -->', source, flags=re.S)
    # Validate every input and destination before replacing anything.
    for path, svg in assets:
        badges.write_if_changed(path, svg)
    badges.write_if_changed(readme, source)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--readme', type=Path, default=Path('README.md'))
    parser.add_argument('--output', type=Path, default=Path('benchmark-badges'))
    parser.add_argument('--workflow-url', default=os.environ.get('GITHUB_SERVER_URL', 'https://github.com').rstrip('/')
                        + '/' + os.environ.get('GITHUB_REPOSITORY', 'RishabhRathi-Dev/Setu') + '/actions/workflows/ci.yml')
    args = parser.parse_args()
    render(args.readme, args.output, args.workflow_url)


if __name__ == '__main__':
    main()
