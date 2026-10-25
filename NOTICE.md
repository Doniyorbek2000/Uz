# Third-party data

## English and Russian word lists

`shared/dictionaries/en.txt` and `shared/dictionaries/ru.txt` are derived from
[wordfreq](https://github.com/rspeer/wordfreq) by Robyn Speer, whose data is licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). These two files are
distributed under the same license.

## Uzbek word lists

`shared/dictionaries/uz_latn.txt`, `uz_latn_2027.txt`, `uz_cyrl.txt` and `tools/uz_corpus_freq.tsv`
contain word frequencies counted by `tools/build_uz_corpus.py` from:

- **Uzbek news text-classification dataset** — E. Kuriyozov, U. Salaev, S. Matlatipov, G. Matlatipov,
  *Text classification dataset and analysis for Uzbek language*, 2023.
  [Zenodo record 7677431](https://zenodo.org/records/7677431), licensed under
  [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- **Uzbek Wikipedia** — article text from the `uzwiki` database dump, © Wikipedia contributors,
  licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

merged with the project's own seed list `tools/uz_seed.txt`. Only word forms and their counts are kept,
no running text. Because part of the input is CC BY-SA, these files are distributed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
