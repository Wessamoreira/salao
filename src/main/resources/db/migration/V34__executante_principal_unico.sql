-- RT-ATD-005 — o assistente entra; o principal continua sendo um só.

-- ---------------------------------------------------------------------------
-- Um item de serviço tem exatamente UM executante principal.
--
-- Dois principais no mesmo item é o defeito que paga duas comissões cheias
-- pelo mesmo trabalho — e ninguém percebe olhando a comanda, porque as duas
-- linhas parecem certas. Só aparece no fechamento do mês, quando o repasse
-- não fecha com o faturamento.
--
-- A regra existia desde RT-ATD-002 (um executante por item, criado junto com
-- ele) e valia só porque ninguém tinha escrito o segundo caminho. Ao abrir a
-- porta do assistente, ela precisa passar a ser garantida pelo banco.
-- ---------------------------------------------------------------------------
create unique index um_principal_por_item
  on executante (item_comanda_id) where papel = 'PRINCIPAL';
