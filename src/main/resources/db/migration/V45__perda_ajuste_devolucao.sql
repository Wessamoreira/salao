-- RT-EST-005 — perda, ajuste e devolução com motivo obrigatório.

-- ---------------------------------------------------------------------------
-- PRIMEIRO: tornar verdadeira uma frase que o glossário já afirmava.
--
-- `01-glossario` descreve o movimento de estoque como "evento IMUTÁVEL que
-- altera saldo". A role da aplicação tinha INSERT, SELECT, UPDATE e DELETE.
--
-- O razão (V40), o vale (V41) e o fechamento (V42) todos impõem a imutabilidade
-- pelo banco, com a mesma justificativa escrita três vezes — "imutabilidade não
-- é convenção, é permissão". O movimento de estoque, que é onde a ideia
-- começou, era o único que dependia de disciplina de quem escreve o código.
--
-- Importa mais justamente aqui. Perda e ajuste são exatamente os movimentos que
-- alguém vai querer "consertar" com um UPDATE: registrou 5 de perda e eram 3.
-- A correção certa é um movimento contrário, que deixa rastro dos dois fatos;
-- o UPDATE apaga o erro e com ele a explicação.
--
-- Nada no código faz UPDATE nem DELETE nesta tabela hoje — conferido antes de
-- revogar. A devolução de estoque (RT-ATD-007) já funciona por INSERT de
-- movimento contrário, que é o padrão que este revoke transforma em regra.
-- ---------------------------------------------------------------------------
revoke update, delete on movimento_estoque from salao_app;

-- ---------------------------------------------------------------------------
-- Devolução ao FORNECEDOR é outro fato que devolução DA CLIENTE.
--
-- `DEVOLUCAO` já existe e já é usada: é a volta das unidades quando uma comanda
-- é reaberta ou estornada (RT-ATD-007), com quantidade POSITIVA, para os lotes
-- de onde saíram.
--
-- Mandar mercadoria de volta ao fornecedor é o oposto: o estoque SAI, a
-- contraparte é outra, e a pergunta "quanto devolvemos ao fornecedor este mês?"
-- é uma pergunta de compras, não de atendimento. Com um tipo só, ela dependeria
-- de filtrar por sinal e de ninguém nunca ter misturado.
--
-- É a mesma razão pela qual o razão separa RECEITA_SERVICO de RECEITA_PRODUTO:
-- mesmo sinal, mesma direção, significados diferentes. Aqui os sinais são até
-- OPOSTOS, então o argumento é mais forte.
--
-- O tipo antigo NÃO é renomeado. A tabela acabou de ficar append-only e as
-- linhas existentes são todas devolução de cliente: reescrever histórico para
-- deixar um nome simétrico é pior do que conviver com a assimetria.
-- ---------------------------------------------------------------------------
alter table movimento_estoque drop constraint movimento_estoque_tipo_check;
alter table movimento_estoque
  add constraint movimento_estoque_tipo_check
  check (tipo in ('ENTRADA', 'VENDA', 'CONSUMO_INTERNO', 'PERDA', 'AJUSTE',
                  'DEVOLUCAO', 'DEVOLUCAO_AO_FORNECEDOR'));

-- ---------------------------------------------------------------------------
-- O MOTIVO É OBRIGATÓRIO, e quem garante é o banco.
--
-- É o título da rotina, e deixá-lo só no caso de uso significaria que o próximo
-- caminho de escrita — o inventário (RT-EST-006), a ficha técnica (RT-EST-007) —
-- pode gravar uma perda sem explicação sem nada reclamar.
--
-- Só para os três tipos que exigem decisão humana. Venda, consumo interno e
-- entrada têm origem (item de comanda, serviço, nota) e a origem já explica.
-- ---------------------------------------------------------------------------
alter table movimento_estoque
  add constraint movimento_com_motivo
  check (
    tipo not in ('PERDA', 'AJUSTE', 'DEVOLUCAO_AO_FORNECEDOR')
    or (observacao is not null and length(btrim(observacao)) >= 3)
  );

comment on column movimento_estoque.observacao is
  'RT-EST-005: OBRIGATÓRIO em PERDA, AJUSTE e DEVOLUCAO_AO_FORNECEDOR (constraint '
  'movimento_com_motivo). Nos outros tipos, quem explica é a origem.';
