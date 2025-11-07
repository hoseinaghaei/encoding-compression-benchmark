.PHONY: all clean build run-encoding run-compression run-hybrid run-all clean-results clean-all deps

# Directories
SRC_DIR = src
BIN_DIR = bin
LIB_DIR = lib

# Java compiler settings
JAVAC = javac
JAVA = java
CLASSPATH = $(BIN_DIR):$(LIB_DIR)/*

# Source files
SOURCES = $(wildcard $(SRC_DIR)/*.java)

all: build

# Download dependencies
deps:
	@echo "Downloading dependencies..."
	@chmod +x download_deps.sh
	@./download_deps.sh

# Build all Java files
build: deps
	@echo "Building project..."
	@mkdir -p $(BIN_DIR)
	@$(JAVAC) -cp "$(LIB_DIR)/*" -d $(BIN_DIR) $(SOURCES)
	@echo "Build completed!"

# Run encoding benchmark
run-encoding: build
	@echo "Running encoding benchmark..."
	@$(JAVA) -cp "$(CLASSPATH)" EncodingBenchmarkNew 42

# Run compression benchmark
run-compression: build
	@echo "Running compression benchmark..."
	@$(JAVA) -cp "$(CLASSPATH)" CompressionBenchmarkNew 42

# Run hybrid benchmark
run-hybrid: build
	@echo "Running hybrid benchmark..."
	@$(JAVA) -cp "$(CLASSPATH)" HybridBenchmarkNew 42

# Run all benchmarks
run-all: run-encoding run-compression run-hybrid
	@echo "All benchmarks completed!"

# Clean results
clean-results:
	@echo "Cleaning result files..."
	@rm -f encoding_res.csv compression_res.csv hybrid_res.csv

# Clean build artifacts
clean:
	@echo "Cleaning build artifacts..."
	@rm -rf $(BIN_DIR)

# Clean everything including dependencies
clean-all: clean clean-results
	@echo "Cleaning dependencies..."
	@rm -rf $(LIB_DIR)
	@echo "All cleaned!"

# Help
help:
	@echo "Available targets:"
	@echo "  deps            - Download required dependencies"
	@echo "  build           - Compile all Java files"
	@echo "  run-encoding    - Run encoding benchmark"
	@echo "  run-compression - Run compression benchmark"
	@echo "  run-hybrid      - Run hybrid benchmark"
	@echo "  run-all         - Run all benchmarks"
	@echo "  clean           - Clean build artifacts"
	@echo "  clean-results   - Clean result CSV files"
	@echo "  clean-all       - Clean everything"
