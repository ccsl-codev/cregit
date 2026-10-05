# srcML 1.1.0 built from its release tag, for the libsrcml headers. Later
# revisions change the tokens of files that 1.1.0 parses (srcML/srcML#2325 marks
# up some macro calls as expressions; UTF-8 input is no longer read as Latin-1).
{ lib
, stdenv
, fetchFromGitHub
, fetchurl
, cmake
, ninja
, jdk_headless
, libxml2
, libxslt
, libarchive
, curl
, libiconv
}:

let
  antlr = fetchurl {
    url = "https://www.antlr2.org/download/antlr-2.7.7.tar.gz";
    hash = "sha256-hTrrAhrvdYa9op50prAwBry1ZadVyGtmAy2Owxtn27k=";
  };

  tinysha1 = fetchFromGitHub {
    owner = "mohaps";
    repo = "tinysha1";
    rev = "2795aa8de91b1797defdfbff61ed93b22b5ced81";
    hash = "sha256-oPZpqJKf6SHOD/bWG5IqtoODGlnQ9qsBiZ9FAY2Awd4=";
  };

  ctpl = fetchFromGitHub {
    owner = "vit-vit";
    repo = "CTPL";
    rev = "437e135dbd94eb65b45533d9ce8ee28b5bd37b6d"; # tag v.0.0.2
    hash = "sha256-O9l7k1/2fVfEcyfJmWzXmju6TRQHK+NZFoaYmdd5KRg=";
  };

  cli11 = fetchurl {
    url = "https://github.com/CLIUtils/CLI11/releases/download/v2.5.0/CLI11.hpp";
    hash = "sha256-S/CpSQqnIJF2zNpwVE+VQT5ZTSIHzKM8nNGN7RiaY6Y=";
  };
in
stdenv.mkDerivation {
  pname = "srcml";
  version = "1.1.0";

  src = fetchFromGitHub {
    owner = "srcML";
    repo = "srcML";
    rev = "af1efb78ddd6fc4a8fc75bc6dc3c2e22c5fd812d"; # tag v1.1.0
    hash = "sha256-AdPVoUKI4/EigJz34BuGIbbN0jUGZTIxOQbmXr8IImM=";
  };

  nativeBuildInputs = [ cmake ninja jdk_headless ];

  buildInputs = [ libxml2 libxslt libarchive curl ]
    ++ lib.optionals stdenv.hostPlatform.isDarwin [ libiconv ];

  # Without this the manpage step downloads a prebuilt page from a bare IP over
  # plain HTTP, so $out carries no man page.
  postPatch = ''
    substituteInPlace doc/CMakeLists.txt \
      --replace-fail "add_subdirectory(manpage)" "# add_subdirectory(manpage)"
  '';

  # CMake edits the antlr and CTPL sources in place after fetching them, so each
  # dependency needs a writable copy.
  preConfigure = ''
    deps="$NIX_BUILD_TOP/fetchcontent"
    mkdir -p "$deps/cli11" "$deps/libarchive-include"
    tar xf ${antlr} -C "$deps"
    cp -r --no-preserve=mode,ownership ${tinysha1} "$deps/tinysha1"
    cp -r --no-preserve=mode,ownership ${ctpl} "$deps/ctpl"
    cp --no-preserve=mode,ownership ${cli11} "$deps/cli11/CLI11.hpp"
    cp --no-preserve=mode,ownership \
      ${lib.getDev libarchive}/include/archive.h \
      ${lib.getDev libarchive}/include/archive_entry.h \
      "$deps/libarchive-include/"

    cmakeFlagsArray+=(
      "-DFETCHCONTENT_SOURCE_DIR_ANTLRSRC=$deps/antlr-2.7.7"
      "-DFETCHCONTENT_SOURCE_DIR_TINYSHA1=$deps/tinysha1"
      "-DFETCHCONTENT_SOURCE_DIR_CTPL_STL_SRC=$deps/ctpl"
      "-DFETCHCONTENT_SOURCE_DIR_CLI11=$deps/cli11"
      "-DFETCHCONTENT_SOURCE_DIR_LIBARCHIVEINCLUDE1=$deps/libarchive-include"
      "-DFETCHCONTENT_SOURCE_DIR_LIBARCHIVEINCLUDE=$deps/libarchive-include"
    )
  '';

  cmakeFlags = [
    (lib.cmakeBool "FETCHCONTENT_FULLY_DISCONNECTED" true)
    (lib.cmakeBool "BUILD_CLIENT_TESTS" false)
  ];

  doCheck = false;

  meta = {
    description = "Infrastructure for exploration, analysis and manipulation of source code";
    homepage = "https://www.srcML.org";
    license = lib.licenses.gpl3Only;
    mainProgram = "srcml";
    platforms = [ "x86_64-linux" "aarch64-darwin" ];
  };
}
