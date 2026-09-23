# srcML at a pinned source revision. The 1.1.0 release binary segfaults under
# `--position` (srcML/srcML#2395, fixed upstream by cebd3ab, still unreleased),
# and the pipeline always passes `--position`.
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
, libxcb
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

  clip = fetchFromGitHub {
    owner = "dacap";
    repo = "clip";
    rev = "f2bd226fd96f3431f4675d9c13aa7334dd06ddda"; # tag v1.15
    hash = "sha256-qviH7XwNOTA3sMnfwaJL+GqXHxeG6giIZneuTeB+Lm8=";
  };

  cli11 = fetchurl {
    url = "https://github.com/CLIUtils/CLI11/releases/download/v2.7.0/CLI11.hpp";
    hash = "sha256-WqjWN4eSFj0eDfZC7uMTrO7KaKU0ZFZq5yBNWcL14Wg=";
  };
in
stdenv.mkDerivation {
  pname = "srcml";
  version = "1.1.0-unstable-2026-09-20";

  src = fetchFromGitHub {
    owner = "srcML";
    repo = "srcML";
    rev = "8a3a629d84ac0e0228a5757e6b9876742f88ed25";
    hash = "sha256-stn1VP+D9Awd9B+r3UWIjHJUkoNU52SOJndX9hLEnrY=";
  };

  nativeBuildInputs = [ cmake ninja jdk_headless ];

  buildInputs = [ libxml2 libxslt libarchive curl ]
    ++ lib.optionals stdenv.hostPlatform.isLinux [ libxcb ]
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
    cp -r --no-preserve=mode,ownership ${clip} "$deps/clip"
    cp --no-preserve=mode,ownership ${cli11} "$deps/cli11/CLI11.hpp"
    cp --no-preserve=mode,ownership \
      ${lib.getDev libarchive}/include/archive.h \
      ${lib.getDev libarchive}/include/archive_entry.h \
      "$deps/libarchive-include/"

    cmakeFlagsArray+=(
      "-DFETCHCONTENT_SOURCE_DIR_ANTLRSRC=$deps/antlr-2.7.7"
      "-DFETCHCONTENT_SOURCE_DIR_TINYSHA1=$deps/tinysha1"
      "-DFETCHCONTENT_SOURCE_DIR_CTPL_STL_SRC=$deps/ctpl"
      "-DFETCHCONTENT_SOURCE_DIR_CLIP=$deps/clip"
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
