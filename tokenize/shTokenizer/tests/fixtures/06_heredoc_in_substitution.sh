# fixture: contrib/rerere-train.sh:1-39 @ 18e66859
#!/bin/sh
# Copyright (c) 2008, Nanako Shiraishi
# Prime rerere database from existing merge commits

me=rerere-train
USAGE=$(cat <<-EOF
usage: $me [--overwrite] <rev-list-args>

    -h, --help            show the help
    -o, --overwrite       overwrite any existing rerere cache
EOF
)

SUBDIRECTORY_OK=Yes

overwrite=0

while test $# -gt 0
do
	opt="$1"
	case "$opt" in
	-h|--help)
		echo "$USAGE"
		exit 0
		;;
	-o|--overwrite)
		overwrite=1
		shift
		break
		;;
	--)
		shift
		break
		;;
	*)
		break
		;;
	esac
done
