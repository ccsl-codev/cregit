# fixture: t/t7813-grep-icase-iso.sh:7-17 @ 18e66859
test_expect_success GETTEXT_ISO_LOCALE 'setup' '
	printf "TILRAUN: Halló Heimur!" >file &&
	git add file &&
	LC_ALL="$is_IS_iso_locale" &&
	export LC_ALL
'

test_expect_success GETTEXT_ISO_LOCALE,PCRE 'grep pcre string' '
	git grep --perl-regexp -i "TILRAUN: H.lló Heimur!" &&
	git grep --perl-regexp -i "TILRAUN: H.LLÓ HEIMUR!"
'
