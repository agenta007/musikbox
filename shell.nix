# Build and run the musikbox desktop app on NixOS:
#   nix-shell --run desktop/install.sh        build and install, with a launcher that works on NixOS
#   nix-shell --run './gradlew :desktop:run'  run from source
{ pkgs ? import <nixpkgs> { } }:

pkgs.mkShell {
  packages = [ pkgs.jdk21 pkgs.mpv ];

  # Compose draws with Skia, a prebuilt library that looks for these in the usual Linux places,
  # which NixOS doesn't have. /run/opengl-driver/lib holds the system's GPU driver.
  LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath (with pkgs; [
    libGL
    fontconfig
    stdenv.cc.cc.lib
    # nixpkgs renamed xorg.libX11 to libx11 (and so on); take whichever this nixpkgs has.
    (pkgs.libx11 or xorg.libX11)
    (pkgs.libxext or xorg.libXext)
    (pkgs.libxrender or xorg.libXrender)
    (pkgs.libxtst or xorg.libXtst)
    (pkgs.libxi or xorg.libXi)
    (pkgs.libxrandr or xorg.libXrandr)
  ]) + ":/run/opengl-driver/lib";
}
