# fixture: git-sh-setup.sh:49-58 @ 18e66859
die () {
	die_with_status 1 "$@"
}

die_with_status () {
	status=$1
	shift
	printf >&2 '%s\n' "$*"
	exit "$status"
}
