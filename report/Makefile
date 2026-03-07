.PHONY: all build clean rebuild watch

TYPST_SRC := main.typ
TYPST_OUT := "main.pdf"

all: build

build: $(TYPST_OUT)

$(TYPST_OUT): $(TYPST_SRC) title.typ
	typst compile $(TYPST_SRC) $(TYPST_OUT)

clean:
	rm -f $(TYPST_OUT)

rebuild: clean build

watch:
	typst watch $(TYPST_SRC) $(TYPST_OUT)
