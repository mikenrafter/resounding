{
  description = "Resounding — Fabric mod dev shell (GPU Minecraft + IntelliJ debug)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.05";
    nixpkgs-unstable.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, nixpkgs-unstable, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        lib = nixpkgs.lib;
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfreePredicate = pkg:
            lib.hasPrefix "modrinth-app" (lib.getName pkg);
        };
        pkgs-unstable = import nixpkgs-unstable {
          inherit system;
          config.allowUnfreePredicate = pkg:
            lib.hasPrefix "modrinth-app" (lib.getName pkg);
        };

        jdk = pkgs.jdk21;

        mcLibs = with pkgs; [
          libGL
          libglvnd
          glfw3-minecraft
          openal
          libpulseaudio
          alsa-lib
          flite
          udev
          xorg.libX11
          xorg.libXcursor
          xorg.libXrandr
          xorg.libXi
          xorg.libXxf86vm
          xorg.libXinerama
          xorg.libXext
          libxkbcommon
          systemd
          zlib
          (lib.getLib stdenv.cc.cc)
        ];

        mcLibPath = lib.makeLibraryPath mcLibs;
        idea = pkgs.jetbrains.idea-community;

        shellEnv = ''
          export JAVA_HOME="${jdk}"
          export PATH="${lib.makeBinPath [ jdk pkgs.gradle ]}:$PATH"
          export MINECRAFT_LD_LIBRARY_PATH="${mcLibPath}"
          export LD_LIBRARY_PATH="''${MINECRAFT_LD_LIBRARY_PATH}''${LD_LIBRARY_PATH:+:}$LD_LIBRARY_PATH"
          export __GLX_VENDOR_LIBRARY_NAME=nvidia
          export _JAVA_AWT_WM_NONREPARENTING=1
        '';

        repoRootHelper = ''
          resounding_repo_root() {
            if [[ -n "''${RESOUNDING_REPO_ROOT:-}" && -f "''${RESOUNDING_REPO_ROOT}/build.gradle" ]]; then
              printf '%s\n' "''${RESOUNDING_REPO_ROOT}"
              return 0
            fi
            if [[ -f ./build.gradle ]]; then
              pwd
              return 0
            fi
            local root
            root="$(git -C "''${PWD}" rev-parse --show-toplevel 2>/dev/null || true)"
            if [[ -n "$root" && -f "$root/build.gradle" ]]; then
              printf '%s\n' "$root"
              return 0
            fi
            echo "Could not find the resounding checkout (need build.gradle). Run from repo/ or set RESOUNDING_REPO_ROOT." >&2
            return 1
          }
        '';

        devSetup = pkgs.writeShellScriptBin "resounding-dev-setup" ''
          set -euo pipefail
          ${repoRootHelper}
          cd "$(resounding_repo_root)"
          echo "==> Gradle sync + Minecraft sources (first run may take several minutes)…"
          ./gradlew --no-daemon genSources genIdeaWorkspace
          echo "==> Dev environment ready."
          echo "    Open IntelliJ:  resounding-idea"
          echo "    Run client:     ./gradlew runClient   (or Minecraft Client in IDEA)"
        '';

        ideaLauncher = pkgs.writeShellScriptBin "resounding-idea" ''
          set -euo pipefail
          ${shellEnv}
          ${repoRootHelper}
          exec ${idea}/bin/idea-community "$(resounding_repo_root)" "$@"
        '';
      in
      {
        devShells.default = pkgs.mkShell {
          packages = with pkgs; [
            jdk
            gradle
            git
            idea
            pkgs-unstable.modrinth-app
            devSetup
            ideaLauncher
          ];

          buildInputs = mcLibs;

          shellHook = ''
            ${shellEnv}
            export RESOUNDING_REPO_ROOT="$PWD"
            ${repoRootHelper}
            echo "Resounding dev shell — JAVA_HOME=$JAVA_HOME"
            echo "  resounding-dev-setup   # genSources + IntelliJ workspace"
            echo "  resounding-idea        # IDEA Community on this repo"
            echo "  modrinth-app           # Modrinth launcher"
          '';
        };

        apps.idea = {
          type = "app";
          program = "${ideaLauncher}/bin/resounding-idea";
        };

        apps.dev-setup = {
          type = "app";
          program = "${devSetup}/bin/resounding-dev-setup";
        };
      });
}
