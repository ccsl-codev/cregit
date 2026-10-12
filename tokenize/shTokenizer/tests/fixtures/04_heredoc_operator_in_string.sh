# fixture: t/lib-t6000.sh:125-135 @ 18e66859
test_output_expect_success()
{
	_description=$1
	_test=$2
	test $# -eq 2 ||
	error "usage: test_output_expect_success description test <<EOF ... EOF"

	_name=$(echo $_description | name_from_description)
	cat >"$_name.expected"
	test_expect_success "$_description" "check_output $_name \"$_test\""
}
