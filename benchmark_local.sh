#!/bin/bash

###############################################################################
# Simple Multimodal LLM Benchmark Script
#
# Uses 3 images from the 'images' folder
#
# Usage: ./benchmark_local.sh [server_ip] [port] [iterations]
# Example: ./benchmark_local.sh 192.168.124.3 8888 5
###############################################################################

# Configuration
IMAGE_DIR="images"
SERVER_IP=${1:-"192.168.124.3"}
PORT=${2:-8888}
ITERATIONS=${3:-5}
SERVER_URL="http://$SERVER_IP:$PORT/v1/chat/completions"
MODEL_NAME="gemma-3n-E2B-it-int4.litertlm"

# Color codes
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Multimodal LLM Benchmark${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Image folder: $IMAGE_DIR"
echo "Server: http://$SERVER_IP:$PORT"
echo "Iterations: $ITERATIONS"
echo ""

# Check if images folder exists
if [ ! -d "$IMAGE_DIR" ]; then
    echo "Error: '$IMAGE_DIR' folder not found!"
    echo "Please create an 'images' folder with 3 images."
    exit 1
fi

# Get images (use first 3 found)
IMAGES=$(ls "$IMAGE_DIR"/*.{jpg,jpeg,png,JPG,JPEG,PNG} 2>/dev/null | head -3)
NUM_IMAGES=$(echo "$IMAGES" | grep -c . || echo 0)

if [ "$NUM_IMAGES" -eq 0 ]; then
    echo "Error: No images found in '$IMAGE_DIR' folder!"
    exit 1
fi

echo -e "${GREEN}Found $NUM_IMAGES images${NC}"
for img in $IMAGES; do
    echo "  - $(basename "$img")"
done
echo ""

# Encode images to temp files
echo -e "${BLUE}Encoding images...${NC}"
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

i=1
for img in $IMAGES; do
    base64 -i "$img" > "$TEMP_DIR/img$i.txt" 2>/dev/null
    echo "  [$i/3] $(basename "$img")"
    i=$((i+1))
done
echo ""

# Build JSON payload
echo -e "${BLUE}Building request...${NC}"

# Read encoded images
IMG1=$(cat "$TEMP_DIR/img1.txt" | tr -d '\n')
IMG2=$(cat "$TEMP_DIR/img2.txt" | tr -d '\n')
IMG3=$(cat "$TEMP_DIR/img3.txt" | tr -d '\n')

# Create JSON
JSON=$(cat <<EOF
{
  "model": "$MODEL_NAME",
  "messages": [
    {
      "role": "user",
      "content": [
        {"type": "text", "text": "Describe each image in one short sentence."},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,$IMG1"}},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,$IMG2"}},
        {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,$IMG3"}}
      ]
    }
  ]
}
EOF
)

# Run benchmark
echo -e "${BLUE}Running $ITERATIONS iterations...${NC}"
echo ""

TIMES=()

for iter in $(seq 1 $ITERATIONS); do
    echo -n "[$iter/$ITERATIONS] "

    START=$(python3 -c "import time; print(int(time.time() * 1000))")

    RESPONSE=$(curl -s -X POST "$SERVER_URL" \
        -H "Content-Type: application/json" \
        -d "$JSON" \
        --max-time 300)

    END=$(python3 -c "import time; print(int(time.time() * 1000))")
    DURATION=$((END - START))

    if echo "$RESPONSE" | grep -q '"choices"'; then
        echo -e "${GREEN}${DURATION}ms${NC}"
        TIMES+=($DURATION)
    else
        echo -e "${YELLOW}Failed${NC}"
    fi

    if [ $iter -lt $ITERATIONS ]; then
        sleep 1
    fi
done

# Calculate results
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Results${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

TOTAL=0
for time in "${TIMES[@]}"; do
    TOTAL=$((TOTAL + time))
done

AVG=$((TOTAL / ITERATIONS))

echo "Times: ${TIMES[@]} ms"
echo ""
echo -e "${GREEN}Average: ${AVG}ms${NC}"
echo -e "${GREEN}Per image: $((AVG / 3))ms${NC}"
echo -e "${GREEN}Images/sec: $(echo "scale=2; 3000 / $AVG" | bc)${NC}"
echo ""
