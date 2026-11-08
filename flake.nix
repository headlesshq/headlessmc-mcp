{
  description = "Development shell for headlessmc-mcp";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs =
    { nixpkgs, ... }:
    let
      inherit (nixpkgs) lib;

      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];

      forAllSystems = f: lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      jdks = pkgs: {
        "25" = pkgs.temurin-bin-25;
      };

      # native-image is only used in CI, so the shell has no GraalVM
      mkJavaShell =
        pkgs: primary:
        let
          available = jdks pkgs;
          jdk = available.${primary};
        in
        pkgs.mkShell (
          {
            packages = [ jdk ];

            JAVA_HOME = jdk.home;
          }
          // lib.optionalAttrs (lib.length (lib.attrNames available) > 1) {
            # Gradle ignores org.gradle.java.installations.paths in GRADLE_OPTS,
            # but its toolchain detection scans $SDKMAN_CANDIDATES_DIR/java/*
            SDKMAN_CANDIDATES_DIR = pkgs.linkFarm "jdks" (
              lib.mapAttrs' (version: j: lib.nameValuePair "java/${version}" j.home) available
            );
          }
        );
    in
    {
      devShells = forAllSystems (pkgs: {
        default = mkJavaShell pkgs "25";
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-tree);
    };
}
