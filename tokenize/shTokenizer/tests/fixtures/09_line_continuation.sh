# fixture: t/test-lib-functions.sh:547-549 @ 18e66859
	git -C "$indir" \
	    -c fastimport.unpacklimit=0 \
	    fast-import <"$tmpfile" || return 1
