#!/usr/bin/env perl

# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.

# Print the universal file mask, derived from CregitLanguages::%EXT_LANG.
#
# This exists so run_pipeline_process.sh's default --mask is read from the
# extension table rather than typed out beside it. A mask and a table that are
# maintained separately drift, and both directions are silent: an extension the
# mask names but the table does not kills the run part-way through, and an
# extension the table knows but no mask names leaves that source untokenized
# with nothing to show it happened.
#
# No arguments, no options. One line on stdout, no trailing shell quoting: the
# caller must quote it (the mask contains regex metacharacters).

use strict;
use warnings;
use FindBin;
use lib $FindBin::Bin;
use CregitLanguages;

die "$0 takes no arguments\n" if @ARGV;

print CregitLanguages::file_mask(), "\n";
