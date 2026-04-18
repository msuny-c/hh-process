#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/diagrams"
echo "Rendering PlantUML -> SVG in $(pwd)"
plantuml -tsvg ./*.puml
echo "Done: $(ls -1 ./*.svg 2>/dev/null | wc -l | tr -d ' ') SVG file(s)"
