.PHONY: test expected

PYTHON ?= python3

test:
	$(PYTHON) -m unittest discover -s tests

expected:
	mkdir -p tests/expected
	for fixture in tests/fixtures/*.sh; do \
	    name=$$(basename "$$fixture"); \
	    $(PYTHON) sh_tokenizer.py --position "$$fixture" > "tests/expected/$$name.token"; \
	    $(PYTHON) sh_tokenizer.py "$$fixture" > "tests/expected/$$name.nopos.token"; \
	done
