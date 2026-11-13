# fixture: git-sh-setup.sh:279-289 @ 18e66859
# Generate a virtual base file for a two-file merge. Uses git apply to
# remove lines from $1 that are not in $2, leaving only common lines.
create_virtual_base() {
	sz0=$(wc -c <"$1")
	@DIFF@ -u -La/"$1" -Lb/"$1" "$1" "$2" | git apply --no-add
	sz1=$(wc -c <"$1")

	# If we do not have enough common material, it is not
	# worth trying two-file merge using common subsections.
	expr $sz0 \< $sz1 \* 2 >/dev/null || : >"$1"
}
