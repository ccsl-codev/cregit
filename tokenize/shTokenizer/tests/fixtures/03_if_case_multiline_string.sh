# fixture: git-sh-setup.sh:82-96 @ 18e66859

	if [ -z "$LONG_USAGE" ]
	then
		LONG_USAGE="$(eval_gettext "usage: \$dashless \$USAGE")"
	else
		LONG_USAGE="$(eval_gettext "usage: \$dashless \$USAGE

$LONG_USAGE")"
	fi

	case "$1" in
		-h)
		echo "$LONG_USAGE"
		exit
	esac
