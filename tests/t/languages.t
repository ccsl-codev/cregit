#!/usr/bin/env perl

# The extension table has been stable since 2017 and needs no test. These
# assertions cover the case-insensitive mask, which selects .C and .H, and a
# pipeline default derived from the table rather than written out a second time.

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
