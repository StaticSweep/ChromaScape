#!/usr/bin/env python3
"""Generate the Micro6 artifact binding from ChromaScape's canonical contract; never compiles Micro6."""
import argparse,json,pathlib
parser=argparse.ArgumentParser();parser.add_argument('--micro6-root',required=True);args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
source=root/'src/main/resources/contracts/behavior-semantic-v1.json'
json.loads(source.read_text())
target=pathlib.Path(args.micro6_root)/'runelite-client/src/main/resources/contracts/behavior-semantic-v1.json'
target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes())
print('Generated canonical producer binding')
