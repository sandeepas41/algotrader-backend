#!/bin/bash

# Development server start script for Spring Boot
#
# Usage:
#   ./scripts/dev-start.sh           # Normal start (compile + run)
#   ./scripts/dev-start.sh -q        # Quick start (skip compilation)
#   ./scripts/dev-start.sh -k        # Kill the running server
#   ./scripts/dev-start.sh -r        # Restart (kill + compile + run)
#   ./scripts/dev-start.sh -rq       # Quick restart (kill + run without compile)
#   ./scripts/dev-start.sh -d        # Start with remote debug enabled (port 5005)
#   ./scripts/dev-start.sh -d -dp 5006  # Debug on custom port

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Parse command line arguments
KILL_ONLY=false
RESTART=false
QUICK=false
USE_MVND=false
DEBUG_MODE=false
DEBUG_PORT=5005
DEBUG_SUSPEND=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -k|--kill)
            KILL_ONLY=true
            shift
            ;;
        -r|--restart)
            RESTART=true
            shift
            ;;
        -q|--quick)
            QUICK=true
            shift
            ;;
        -rq|-qr|--restart-quick|--quick-restart)
            RESTART=true
            QUICK=true
            shift
            ;;
        -m|--mvnd)
            USE_MVND=true
            shift
            ;;
        -d|--debug)
            DEBUG_MODE=true
            shift
            ;;
        -dp|--debug-port)
            DEBUG_PORT="$2"
            shift 2
            ;;
        -ds|--debug-suspend)
            DEBUG_SUSPEND=true
            shift
            ;;
        -h|--help)
            echo "Usage: $0 [options]"
            echo ""
            echo "Options:"
            echo "  -k, --kill           Kill the running server and exit"
            echo "  -r, --restart        Restart the server (kill + compile + run)"
            echo "  -q, --quick          Quick start/restart (skip compilation)"
            echo "  -rq, --restart-quick Quick restart (kill + run without compile)"
            echo "  -m, --mvnd           Use mvnd (Maven Daemon) instead of ./mvnw"
            echo "  -d, --debug          Enable remote debugging (default port: 5005)"
            echo "  -dp, --debug-port    Set debug port (default: 5005)"
            echo "  -ds, --debug-suspend Suspend JVM until debugger attaches"
            echo "  -h, --help           Show this help message"
            echo ""
            echo "Debug Examples:"
            echo "  $0 -d               Start with debug on port 5005"
            echo "  $0 -d -dp 5006      Start with debug on port 5006"
            echo "  $0 -d -ds           Start and wait for debugger to attach"
            echo "  $0 -r -d            Restart with debug enabled"
            echo ""
            echo "IntelliJ Setup:"
            echo "  1. Run -> Edit Configurations -> + -> Remote JVM Debug"
            echo "  2. Set Host: localhost, Port: 5005 (or your custom port)"
            echo "  3. Start this script with -d flag"
            echo "  4. Click Debug in IntelliJ to attach"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            echo "Use -h or --help for usage information"
            exit 1
            ;;
    esac
done

# Ensure we're in the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

# Configuration
LOG_FILE="dev.log"
PID_FILE=".dev.pid"
MAX_WAIT_SECONDS=120
HEALTH_CHECK_INTERVAL=2

# Get server port from .env
PORT=$(grep -E '^SERVER_PORT=' .env 2>/dev/null | cut -d'=' -f2)
if [ -z "$PORT" ]; then
    echo -e "${RED}ERROR: SERVER_PORT not found in .env file${NC}"
    echo -e "${YELLOW}Please add SERVER_PORT=<port> to your .env file${NC}"
    exit 1
fi

# Function to kill the server
kill_server() {
    local killed=false

    # Kill by PID file
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p $PID > /dev/null 2>&1; then
            echo -e "${YELLOW}Stopping dev server (PID: $PID)...${NC}"
            kill $PID 2>/dev/null || true

            # Wait for process to stop
            COUNTER=0
            while ps -p $PID > /dev/null 2>&1; do
                sleep 1
                COUNTER=$((COUNTER + 1))
                if [ $COUNTER -gt 10 ]; then
                    echo -e "${YELLOW}Process not stopping, forcing kill...${NC}"
                    kill -9 $PID 2>/dev/null || true
                    break
                fi
            done
            killed=true
        fi
        rm -f "$PID_FILE"
    fi

    # Kill any process running on the port
    EXISTING_PID=$(lsof -ti :$PORT 2>/dev/null || true)
    if [ -n "$EXISTING_PID" ]; then
        echo -e "${YELLOW}Killing process on port $PORT (PID: $EXISTING_PID)...${NC}"
        kill -9 $EXISTING_PID 2>/dev/null || true
        sleep 2
        killed=true
    fi

    if [ "$killed" = true ]; then
        echo -e "${GREEN}✓ Server stopped${NC}"
    else
        echo -e "${YELLOW}No running server found${NC}"
    fi
}

echo -e "${GREEN}Using port: $PORT${NC}"

# Handle kill-only mode
if [ "$KILL_ONLY" = true ]; then
    kill_server
    exit 0
fi

# Handle restart mode (kill without prompting)
if [ "$RESTART" = true ]; then
    kill_server
    echo ""
else
    # Normal mode: check if server is running and prompt
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if ps -p $PID > /dev/null 2>&1; then
            echo -e "${YELLOW}Dev server is already running with PID: $PID${NC}"
            echo -n "Do you want to restart it? [y/N] "
            read -r response
            if [[ "$response" =~ ^([yY][eE][sS]|[yY])$ ]]; then
                kill_server
                echo ""
            else
                echo "Exiting without changes"
                exit 0
            fi
        else
            # Clean up stale PID file
            echo -e "${YELLOW}Found stale PID file (process $PID not running), cleaning up...${NC}"
            rm -f "$PID_FILE"
        fi
    fi

    # Kill any process running on the port (in case started by other means)
    EXISTING_PID=$(lsof -ti :$PORT 2>/dev/null || true)
    if [ -n "$EXISTING_PID" ]; then
        echo -e "${YELLOW}Found existing process on port $PORT (PID: $EXISTING_PID), stopping...${NC}"
        kill -9 $EXISTING_PID 2>/dev/null || true
        sleep 2
    fi
fi

# Check if Maven wrapper exists
if [ ! -f "./mvnw" ]; then
    echo -e "${RED}Maven wrapper (mvnw) not found${NC}"
    exit 1
fi

# Load environment variables from .env
JVM_ARGS=""
if [ -f .env ]; then
    while IFS='=' read -r key value || [[ -n "$key" ]]; do
        # Skip comments and empty lines
        [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]] && continue
        # Trim whitespace from key
        key=$(echo "$key" | xargs)
        # Export uppercase variables as env vars
        if [[ "$key" =~ ^[A-Z_]+$ ]]; then
            export "$key=$value"
        # Collect Spring properties (lowercase with dots) for JVM args
        elif [[ "$key" =~ ^[a-z] ]]; then
            JVM_ARGS="$JVM_ARGS -D$key=$value"
        fi
    done < .env
    echo -e "${GREEN}Loaded environment from .env${NC}"
fi

# Rotate logs
if [ -f "$LOG_FILE" ]; then
    mv "$LOG_FILE" "$LOG_FILE.prev"
fi

# Build Maven command
if [ "$USE_MVND" = true ]; then
    MAVEN_EXEC="$HOME/.sdkman/candidates/mvnd/current/bin/mvnd"
    if [ ! -f "$MAVEN_EXEC" ]; then
        echo -e "${RED}mvnd not found at $MAVEN_EXEC${NC}"
        echo -e "${YELLOW}Install with: sdk install mvnd${NC}"
        exit 1
    fi
    echo -e "${GREEN}Using Maven Daemon (mvnd)${NC}"
else
    MAVEN_EXEC="./mvnw"
fi
MAVEN_CMD="$MAVEN_EXEC spring-boot:run -Dmaven.test.skip=true -Dspotless.check.skip=true -Dfmt.skip=true -Dmaven.gitcommitid.skip=true -T 1C"

if [ "$QUICK" = true ]; then
    # Quick mode: skip compilation (use existing compiled classes)
    if [ ! -d "target/classes" ]; then
        echo -e "${YELLOW}Warning: target/classes not found. Running full compile...${NC}"
    else
        echo -e "${GREEN}Quick mode: skipping compilation${NC}"
        MAVEN_CMD="$MAVEN_CMD -Dmaven.main.skip=true -Dmaven.test.skip=true"
    fi
fi

# Add debug JVM arguments if debug mode is enabled
if [ "$DEBUG_MODE" = true ]; then
    SUSPEND_FLAG="n"
    if [ "$DEBUG_SUSPEND" = true ]; then
        SUSPEND_FLAG="y"
        echo -e "${YELLOW}Debug suspend enabled - JVM will wait for debugger to attach${NC}"
    fi
    DEBUG_ARGS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=$SUSPEND_FLAG,address=*:$DEBUG_PORT"
    JVM_ARGS="$DEBUG_ARGS $JVM_ARGS"
    echo -e "${GREEN}Remote debugging enabled on port $DEBUG_PORT${NC}"
fi

MAVEN_CMD="$MAVEN_CMD -Dspring-boot.run.jvmArguments=\"$JVM_ARGS\""

if [ "$DEBUG_MODE" = true ]; then
    echo -e "${GREEN}Starting dev server on port $PORT with remote debug on port $DEBUG_PORT...${NC}"
else
    echo -e "${GREEN}Starting dev server on port $PORT...${NC}"
fi

# Start Spring Boot server in background with nohup
nohup bash -c "$MAVEN_CMD" > "$LOG_FILE" 2>&1 &
SERVER_PID=$!

# Save PID
echo $SERVER_PID > "$PID_FILE"

echo -e "${GREEN}Server starting in background (PID: $SERVER_PID)${NC}"
echo "  Logs: tail -f $LOG_FILE"
echo ""

# Wait for server to be ready
echo -e "${GREEN}Waiting for server to start (max ${MAX_WAIT_SECONDS}s)...${NC}"
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT_SECONDS ]; do
    # Check if process is still running
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo -e "${RED}Server process died. Check logs: tail -100 $LOG_FILE${NC}"
        rm -f "$PID_FILE"
        exit 1
    fi

    # Check health endpoint
    if curl -s http://localhost:$PORT/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Server is ready on port $PORT (started in ${ELAPSED}s)${NC}"
        if [ "$DEBUG_MODE" = true ]; then
            echo -e "${GREEN}✓ Remote debug listening on port $DEBUG_PORT${NC}"
        fi
        echo ""
        echo "Commands:"
        echo "  ./scripts/dev-start.sh -k     Kill the server"
        echo "  ./scripts/dev-start.sh -r     Restart (with compile)"
        echo "  ./scripts/dev-start.sh -rq    Quick restart (no compile)"
        if [ "$DEBUG_MODE" = true ]; then
            echo ""
            echo "Debug:"
            echo "  IntelliJ: Run -> Attach to Process, or use Remote JVM Debug config"
            echo "  Debug port: $DEBUG_PORT"
        fi
        exit 0
    fi

    # Show progress every 15 seconds
    if [ $((ELAPSED % 15)) -eq 0 ] && [ $ELAPSED -gt 0 ]; then
        echo -e "${YELLOW}Still waiting... (${ELAPSED}s)${NC}"
    fi

    sleep $HEALTH_CHECK_INTERVAL
    ELAPSED=$((ELAPSED + HEALTH_CHECK_INTERVAL))
done

echo -e "${YELLOW}Server may still be starting. Check logs: tail -f $LOG_FILE${NC}"
exit 0
