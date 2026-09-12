-- RT-EST-009 — leitor de código de barras.

-- ---------------------------------------------------------------------------
-- O código do PRODUTO, que não é o código do lote.
--
-- `lote.codigo` já existia e é outra coisa: o número do lote impresso pelo
-- fabricante, que muda a cada caixa. O que o leitor lê na gôndola é o EAN do
-- produto, igual em toda caixa dele — é o que traduz "bipou" em "é shampoo".
-- ---------------------------------------------------------------------------
alter table produto add column codigo_barras text;

-- ---------------------------------------------------------------------------
-- SEM VALIDAÇÃO DE DÍGITO VERIFICADOR, e é decisão.
--
-- EAN-13 tem dígito verificador, e conferi-lo parece rigor. Na prática recusaria
-- cadastro legítimo: produto importado com etiqueta mal impressa, item
-- reembalado pelo distribuidor, e a etiqueta que o próprio salão imprime para o
-- que não tem código nenhum.
--
-- O que importa é que o texto guardado seja EXATAMENTE o que o leitor emite —
-- se ele lê um código inválido, o cadastro precisa poder guardar aquele código
-- inválido, senão o produto nunca é encontrado. Validar aqui só garantiria que
-- o sistema recusasse a realidade.
--
-- O que É imposto: sem espaço em branco (leitores anexam CR/LF, e um espaço
-- perdido torna o código inencontrável) e não vazio.
-- ---------------------------------------------------------------------------
alter table produto
  add constraint codigo_barras_sem_espaco
  check (codigo_barras is null
         or (length(btrim(codigo_barras)) > 0 and codigo_barras !~ '\s'));

-- ---------------------------------------------------------------------------
-- Único POR SALÃO, não global: dois salões vendem o mesmo shampoo, com o mesmo
-- EAN. Parcial porque a maioria dos produtos não tem código — e `unique` sem o
-- filtro trataria cada nulo como distinto, o que funcionaria, mas o índice
-- carregaria linhas que nunca são consultadas.
-- ---------------------------------------------------------------------------
create unique index um_codigo_de_barras_por_salao
  on produto (estabelecimento_id, codigo_barras)
  where codigo_barras is not null;

comment on column produto.codigo_barras is
  'RT-EST-009: exatamente o que o leitor emite. Sem validação de dígito verificador — '
  'ver V49. Único por estabelecimento.';
