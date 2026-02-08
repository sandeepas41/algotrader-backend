#!/bin/bash

# Convenience alias for: ./scripts/dev-start.sh -k
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/dev-start.sh" -k
