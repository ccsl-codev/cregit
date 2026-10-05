.PHONY: default test expected

XERCES_CFLAGS ?= $(shell pkg-config --cflags xerces-c 2>/dev/null)
XERCES_LIBS   ?= $(shell pkg-config --libs xerces-c 2>/dev/null || echo -lxerces-c-3.2)

ifndef SRCML_PREFIX
$(error SRCML_PREFIX is not set: build inside `devenv shell`, or set it to the prefix of a srcML build with headers)
endif
SRCML_CFLAGS ?= -I$(SRCML_PREFIX)/include
SRCML_LIBS   ?= -L$(SRCML_PREFIX)/lib -Wl,-rpath,$(SRCML_PREFIX)/lib -lsrcml

FIXTURES = StringUtil.java:Java audit.c:C ceiling.cpp:C++ main.c:C srcml2tokenHandlers.cpp:C++

default: srcml2token

srcml2token: srcml2token.cpp srcml2tokenHandlers.cpp srcml2tokenHandlers.hpp srcml2token.hpp
	g++ -g -std=c++11 -O -Wall -pedantic $(XERCES_CFLAGS) $(SRCML_CFLAGS) -o srcml2token srcml2token.cpp srcml2tokenHandlers.cpp $(XERCES_LIBS) $(SRCML_LIBS)

test: srcml2token
	mkdir -p tests/out
	for f in $(FIXTURES); do \
	  ./srcml2token -l "$${f#*:}" "tests/$${f%%:*}" > "tests/out/$${f%%:*}.token" \
	    && diff "tests/expected/$${f%%:*}.token" "tests/out/$${f%%:*}.token" || exit 1; \
	done
	test -f "$$(./srcml2token --libsrcml-path)"
	printf 'int x;\n' > tests/out/unknown.xyz
	! ./srcml2token -l C tests/out/unknown.xyz 2> tests/out/unknown.err
	grep -q 'does not know the extension' tests/out/unknown.err

expected: srcml2token
	mkdir -p tests/expected
	for f in $(FIXTURES); do ./srcml2token -l "$${f#*:}" "tests/$${f%%:*}" > "tests/expected/$${f%%:*}.token"; done
