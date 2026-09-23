{ pkgs, ... }:

let
  srcml = pkgs.callPackage ./nix/srcml.nix { };

  EmailFind = pkgs.perlPackages.buildPerlPackage rec {
    pname = "Email-Find";
    version = "0.10";
    src = pkgs.fetchurl {
      url = "https://cpan.metacpan.org/authors/id/M/MI/MIYAGAWA/Email-Find-0.10.tar.gz";
      sha256 = "sha256-KaqgB9DepKjY23eM8ZHq3sqRxOmIO6So4txNVOjM8g4=";
    };
    propagatedBuildInputs = with pkgs.perlPackages; [ EmailValid ];
    doCheck = false;
    meta.description = "Find email addresses in arbitrary text";
  };

  HTMLFromText = pkgs.perlPackages.buildPerlPackage rec {
    pname = "HTML-FromText";
    version = "2.07";
    src = pkgs.fetchurl {
      url = "https://cpan.metacpan.org/authors/id/R/RJ/RJBS/HTML-FromText-2.07.tar.gz";
      sha256 = "1b93zria8is1kcanwaldyzjcijqcsgrbasvlnmzp1gh584r11q65";
    };
    buildInputs = with pkgs.perlPackages; [ TestMore ];
    propagatedBuildInputs = with pkgs.perlPackages; [ TextAutoformat ] ++ [ EmailFind ];
    doCheck = false;
    meta.description = "Mark up text as HTML";
  };

  perlEnv = pkgs.perl.withPackages (p: [
    p.DBI
    p.DBDSQLite
    EmailFind
    HTMLFromText
    p.HTMLParser
    p.SetScalar
    p.TextAutoformat
  ]);

  legacyJdk = pkgs.temurin-bin-8;
in
{
  packages = [
    pkgs.git
    pkgs.gnumake
    pkgs.gcc
    pkgs.sqlite
    pkgs.git-filter-repo
    pkgs.shellcheck

    srcml
    pkgs.universal-ctags
    pkgs.xercesc
    pkgs.sbt

    perlEnv

    # pytest must come from this python: the system one's duckdb will not load.
    (pkgs.python3.withPackages (ps: [ ps.duckdb ps.pytest ]))
  ];

  # Scala 2.13 + sbt 1.x needs a modern JDK; the legacy sbt 0.13 hack is gone.
  languages.java  = { enable = true; jdk.package = pkgs.jdk21; };
  languages.scala = { enable = true; };
  languages.rust.enable = true;

  env.LEGACY_JAVA_HOME = "${legacyJdk}";

  # `devenv test` gates every tracked shell script. The threshold is `error`
  # because the two scripts already carry 31 lower findings; raise it to
  # `warning` once those are fixed.
  enterTest = ''
    git ls-files -z '*.sh' | xargs -0 --no-run-if-empty shellcheck -S error
    echo "shellcheck: no errors"
    pytest -q
    prove -r $(git ls-files '*/t/*.t' | xargs -n1 dirname | sort -u)
  '';
}
