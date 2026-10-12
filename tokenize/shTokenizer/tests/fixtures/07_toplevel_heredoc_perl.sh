# fixture: t/t9500-gitweb-standalone-no-errors.sh:739-741 @ 18e66859
cat >>gitweb_config.perl <<\EOF &&
$feature{'forks'}{'default'} = [1];
EOF
