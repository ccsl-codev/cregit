#!/usr/bin/env perl

# The extension table has been stable since 2017 and needs no test. These six
# assertions cover what this PR adds: a case-insensitive mask that newly selects
# .C and .H, the override that routes those to C++, and a pipeline default
# derived from the table rather than written out a second time.

use strict;
use warnings;
use Test::More tests => 6;
use FindBin;

use lib "$FindBin::Bin/../../tokenize";
use CregitLanguages;

is(CregitLanguages::language_for_ext('C'), 'C++', ".C routes to C++, per the GNU convention");
is(CregitLanguages::language_for_ext('H'), 'C++', ".H routes to C++");
is(CregitLanguages::language_for_ext('c'), 'C',   ".c still routes to C");
is(CregitLanguages::language_for_ext('h'), 'C',   ".h still routes to C");

my $mask = CregitLanguages::file_mask();
ok("src/Matrix.C" =~ /$mask/, "the mask selects .C, which the old \\.[ch]\$ mask did not");

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

chomp(my $printed = `perl '$root/tokenize/fileMask.pl'`);
is($printed, $mask, "fileMask.pl prints exactly file_mask(), so the pipeline default is derived");
