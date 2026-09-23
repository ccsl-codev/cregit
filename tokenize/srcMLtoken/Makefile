.PHONY: default test expected srcml2token-profile

XERCES_CFLAGS ?= $(shell pkg-config --cflags xerces-c 2>/dev/null)
XERCES_LIBS   ?= $(shell pkg-config --libs xerces-c 2>/dev/null || echo -lxerces-c-3.2)

default: srcml2token

srcml2token: srcml2token.cpp srcml2tokenHandlers.cpp srcml2tokenHandlers.hpp srcml2token.hpp
	g++ -g -std=c++11 -O -Wall -pedantic $(XERCES_CFLAGS) -o srcml2token srcml2token.cpp srcml2tokenHandlers.cpp $(XERCES_LIBS)

# A second binary, beside the first, for profiling/profile-native.sh. -O omits
# the frame pointer on x86-64, and perf cannot walk a stack without one. Kept
# separate so the tokenizer the pipeline runs is never the one built for perf.
srcml2token-profile: srcml2token.cpp srcml2tokenHandlers.cpp srcml2tokenHandlers.hpp srcml2token.hpp
	g++ -g -O2 -fno-omit-frame-pointer -std=c++11 -Wall -pedantic $(XERCES_CFLAGS) -o srcml2token-profile srcml2token.cpp srcml2tokenHandlers.cpp $(XERCES_LIBS)

test: srcml2token
	mkdir -p tests/out
	srcml --position --language Java tests/StringUtil.java | ./srcml2token > tests/out/StringUtil.java.token
	diff tests/expected/StringUtil.java.token tests/out/StringUtil.java.token
	srcml --position --language C tests/audit.c | ./srcml2token > tests/out/audit.c.token
	diff tests/expected/audit.c.token tests/out/audit.c.token
	srcml --position --language C++ tests/ceiling.cpp | ./srcml2token > tests/out/ceiling.cpp.token
	diff tests/expected/ceiling.cpp.token tests/out/ceiling.cpp.token
	srcml --position --language C tests/main.c | ./srcml2token > tests/out/main.c.token
	diff tests/expected/main.c.token tests/out/main.c.token
	srcml --position --language 'C++' tests/srcml2tokenHandlers.cpp | ./srcml2token > tests/out/srcml2tokenHandlers.cpp.token
	diff tests/expected/srcml2tokenHandlers.cpp.token tests/out/srcml2tokenHandlers.cpp.token


expected: srcml2token
	mkdir -p tests/expected
	srcml --position --language 'C++' tests/srcml2tokenHandlers.cpp | ./srcml2token > tests/expected/srcml2tokenHandlers.cpp.token
	srcml --position --language C tests/main.c | ./srcml2token > tests/expected/main.c.token
	srcml --position --language 'C++' tests/ceiling.cpp | ./srcml2token > tests/expected/ceiling.cpp.token
	srcml --position --language C tests/audit.c | ./srcml2token > tests/expected/audit.c.token
	srcml --position --language Java tests/StringUtil.java | ./srcml2token > tests/expected/StringUtil.java.token

