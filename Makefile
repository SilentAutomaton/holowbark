# Holowbark Android — Build Makefile
#
# Prerequisites:
#   Go 1.21+      (in PATH)
#   Java 17+      (JAVA_HOME or in PATH)
#   Android SDK   → set ANDROID_HOME (default: ~/android-sdk)
#   Android NDK   → set ANDROID_NDK_HOME (default: ~/android-sdk/ndk/27.2.12479018)
#   gomobile       → installed via: go install golang.org/x/mobile/cmd/gomobile@latest
#
# Quick start:
#   make setup    — install SDK, NDK, gomobile (first time only)
#   make aar      — build combined holowbark.aar (Yggdrasil + AmneziaWG)
#   make apk      — build debug APK
#   make install  — adb install to connected device
#   make all      — aar + apk

# ─── Paths ───────────────────────────────────────────────────────────────────

ANDROID_HOME    ?= $(HOME)/android-sdk
ANDROID_NDK_HOME?= $(ANDROID_HOME)/ndk/27.2.12479018
JAVA_HOME       ?= /usr/lib/jvm/java-17-openjdk
GOPATH_BIN      := $(shell go env GOPATH)/bin
GOMOBILE        ?= $(GOPATH_BIN)/gomobile
SDKMANAGER      := $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager

# Go sources are fetched here at a pinned revision and patched from deps/patches.
# The directory is a build artefact: everything we change lives in the patches.
GOLIBS_DIR      ?= $(CURDIR)/.deps
YGG_DIR         := $(GOLIBS_DIR)/yggdrasil-go
ANET_DIR        := $(GOLIBS_DIR)/anet
AWG_WRAPPER_DIR := $(YGG_DIR)/contrib/awgmobile

YGG_URL         := https://github.com/yggdrasil-network/yggdrasil-go.git
YGG_REF         := 2527290bfd70776e41763e1a9302736ad9f684f9
ANET_URL        := https://github.com/wlynxg/anet.git
ANET_REF        := v0.0.5

PATCH_DIR       := $(CURDIR)/deps/patches
PATCHES         := $(wildcard $(PATCH_DIR)/*/*.patch)
AWG_WRAPPER_SRC := $(CURDIR)/contrib/awgmobile/awgmobile.go
DEPS_STAMP      := $(YGG_DIR)/.holowbark-deps

AAR_OUT         := $(CURDIR)/app/libs/holowbark.aar
APK_DEBUG       := $(CURDIR)/app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE     := $(CURDIR)/app/build/outputs/apk/release/app-release-unsigned.apk

GRADLEW         := JAVA_HOME=$(JAVA_HOME) ANDROID_HOME=$(ANDROID_HOME) $(CURDIR)/gradlew

export ANDROID_HOME
export ANDROID_NDK_HOME
export JAVA_HOME

# ─── Targets ─────────────────────────────────────────────────────────────────

.PHONY: all aar apk apk-release install install-release clean clean-aar rebuild setup \
        setup-sdk setup-gomobile deps deps-reset clone-deps help

all: aar apk

## Build combined holowbark.aar (Yggdrasil + AmneziaWG via gomobile)
aar: $(AAR_OUT)

$(AAR_OUT): $(DEPS_STAMP)
	@echo "==> Building holowbark.aar …"
	@# app/libs holds only ignored files, so a fresh clone has no such directory.
	@mkdir -p $(dir $(AAR_OUT))
	cd $(YGG_DIR) && \
	  PATH=$(GOPATH_BIN):$$PATH \
	  $(GOMOBILE) bind \
	    -target android \
	    -androidapi 26 \
	    -tags mobile \
	    -o $(AAR_OUT) \
	    -ldflags="-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" \
	    ./contrib/mobile \
	    ./src/config \
	    ./contrib/awgmobile
	@echo "==> $(AAR_OUT) built ($$(du -sh $(AAR_OUT) | cut -f1))"

## Build debug APK
apk: $(AAR_OUT)
	@echo "==> Building debug APK …"
	$(GRADLEW) assembleDebug
	@echo "==> $(APK_DEBUG)"

## Build release APK (unsigned)
apk-release: $(AAR_OUT)
	@echo "==> Building release APK …"
	$(GRADLEW) assembleRelease
	@echo "==> $(APK_RELEASE)"

## Install debug APK to connected device via adb
install: apk
	adb install -r $(APK_DEBUG)

## Install release APK
install-release: apk-release
	adb install -r $(APK_RELEASE)

## Remove build outputs (keeps .aar)
clean:
	$(GRADLEW) clean

## Remove the built .aar (forces gomobile rebuild)
clean-aar:
	rm -f $(AAR_OUT)

## Full rebuild from scratch
rebuild: clean-aar all

# ─── First-time setup ────────────────────────────────────────────────────────

## Install everything needed for first-time build
setup: setup-sdk setup-gomobile deps
	@echo "==> Setup complete. Run 'make aar' to build the Go libraries."

## Download & install Android SDK components (platform-35, build-tools-35, NDK r27)
setup-sdk:
	@echo "==> Installing Android SDK components …"
	@mkdir -p $(ANDROID_HOME)/cmdline-tools
	@if [ ! -f $(SDKMANAGER) ]; then \
	  echo "  Downloading cmdline-tools …"; \
	  curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
	       -o /tmp/cmdline-tools.zip; \
	  unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extracted; \
	  mv /tmp/cmdline-tools-extracted/cmdline-tools $(ANDROID_HOME)/cmdline-tools/latest; \
	  rm /tmp/cmdline-tools.zip; \
	fi
	yes | $(SDKMANAGER) --sdk_root=$(ANDROID_HOME) \
	  "platforms;android-35" \
	  "build-tools;35.0.0" \
	  "platform-tools" \
	  "ndk;27.2.12479018"

## Install gomobile and run gomobile init
setup-gomobile:
	@echo "==> Installing gomobile …"
	go install golang.org/x/mobile/cmd/gomobile@latest
	go install golang.org/x/mobile/cmd/gobind@latest
	ANDROID_NDK_HOME=$(ANDROID_NDK_HOME) $(GOMOBILE) init

## Fetch the pinned Go sources and apply deps/patches
deps: $(DEPS_STAMP)

$(DEPS_STAMP): $(PATCHES) $(AWG_WRAPPER_SRC) $(CURDIR)/deps/apply.sh
	$(CURDIR)/deps/apply.sh $(YGG_URL) $(YGG_REF) $(YGG_DIR) $(PATCH_DIR)/yggdrasil-go
	$(CURDIR)/deps/apply.sh $(ANET_URL) $(ANET_REF) $(ANET_DIR) $(PATCH_DIR)/anet
	@echo "==> Copying the AWG wrapper into yggdrasil-go …"
	@mkdir -p $(AWG_WRAPPER_DIR)
	@cp $(AWG_WRAPPER_SRC) $(AWG_WRAPPER_DIR)/awgmobile.go
	@# The only machine-specific line in the build, which is why it is written
	@# here and not carried in a patch.
	@cd $(YGG_DIR) && go mod edit -replace=github.com/wlynxg/anet=$(ANET_DIR)
	@touch $@

## Delete the dependency checkouts and fetch them again
deps-reset:
	@echo "==> Removing $(YGG_DIR) and $(ANET_DIR)"
	@rm -rf $(YGG_DIR) $(ANET_DIR)
	@$(MAKE) deps

## Former name of deps
clone-deps: deps

# ─── Info ─────────────────────────────────────────────────────────────────────

help:
	@echo ""
	@echo "Holowbark Android — Make targets"
	@echo ""
	@echo "  make setup          First-time setup (SDK + gomobile + Go sources)"
	@echo "  make deps           Fetch the pinned Go sources, apply deps/patches"
	@echo "  make deps-reset     Delete the checkouts under .deps and fetch again"
	@echo "  make aar            Build holowbark.aar (Yggdrasil + AmneziaWG)"
	@echo "  make apk            Build debug APK"
	@echo "  make apk-release    Build release APK (unsigned)"
	@echo "  make install        adb install debug APK"
	@echo "  make all            aar + apk  (default)"
	@echo "  make rebuild        clean-aar + all"
	@echo "  make clean          Gradle clean"
	@echo "  make clean-aar      Remove .aar (forces gomobile rebuild)"
	@echo ""
	@echo "Environment variables:"
	@echo "  ANDROID_HOME     $(ANDROID_HOME)"
	@echo "  ANDROID_NDK_HOME $(ANDROID_NDK_HOME)"
	@echo "  JAVA_HOME        $(JAVA_HOME)"
	@echo "  GOMOBILE         $(GOMOBILE)"
	@echo "  GOLIBS_DIR       $(GOLIBS_DIR)"
	@echo ""
