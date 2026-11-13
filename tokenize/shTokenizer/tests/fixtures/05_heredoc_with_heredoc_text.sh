# fixture: t/t0600-reffiles-backend.sh:93-110 @ 18e66859
test_expect_success 'non-empty directory blocks create' - <<\EOT
	prefix=refs/ne-create &&
	mkdir -p .git/$prefix/foo/bar &&
	: >.git/$prefix/foo/bar/baz.lock &&
	test_when_finished "rm -f .git/$prefix/foo/bar/baz.lock" &&
	cat >expected <<-EOF &&
	fatal: cannot lock ref '$prefix/foo': there is a non-empty directory '.git/$prefix/foo' blocking reference '$prefix/foo'
	EOF
	printf "%s\n" "update $prefix/foo $C" |
	test_must_fail git update-ref --stdin 2>output.err &&
	test_cmp expected output.err &&
	cat >expected <<-EOF &&
	fatal: cannot lock ref '$prefix/foo': unable to resolve reference '$prefix/foo'
	EOF
	printf "%s\n" "update $prefix/foo $D $C" |
	test_must_fail git update-ref --stdin 2>output.err &&
	test_cmp expected output.err
EOT
