#!/usr/bin/env perl

# Covers the case-insensitive mask (.C, .H) and the pipeline default derived
# from the table; the table itself is not tested.

use strict;
use warnings;
use Test::More tests => 2;
use FindBin;

use lib "$FindBin::Bin/../../tokenize";
use CregitLanguages;

my $mask = CregitLanguages::file_mask();
ok("src/Matrix.C" =~ /$mask/, "the mask selects .C, which the old \\.[ch]\$ mask did not");

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

chomp(my $printed = `perl '$root/tokenize/fileMask.pl'`);
is($printed, $mask, "fileMask.pl prints exactly file_mask(), so the pipeline default is derived");
