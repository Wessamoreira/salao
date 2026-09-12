-- RT-FIN-008 — comprovante de atendimento.

-- ---------------------------------------------------------------------------
-- Nenhuma tabela nova.
--
-- O comprovante é uma PROJEÇÃO da comanda fechada, não um registro próprio: ele
-- se monta dos itens, dos descontos e dos pagamentos que já estão gravados, com
-- o snapshot de preço que RN-ATD-010 garante. Guardá-lo seria uma segunda cópia
-- dos mesmos números — e a cópia é a que discorda depois de um estorno.
--
-- O que o estado do envio guarda é a MENSAGEM (V30), pela referência da comanda:
-- é ela que impede o segundo clique em "enviar" virar a segunda mensagem.
-- ---------------------------------------------------------------------------

-- A finalidade nova precisa entrar nos dois `check` — o do consentimento e o da
-- mensagem. Há teste percorrendo `Finalidade.values()` contra o primeiro; sem o
-- segundo, o envio falharia só na hora de gravar a mensagem, em produção.
alter table cliente_consentimento drop constraint cliente_consentimento_finalidade_check;
alter table cliente_consentimento
  add constraint cliente_consentimento_finalidade_check
  check (finalidade in ('LEMBRETE_DE_AGENDAMENTO', 'MARKETING', 'PESQUISA_DE_SATISFACAO',
                        'COMPROVANTE_DE_ATENDIMENTO'));

alter table mensagem drop constraint mensagem_finalidade_check;
alter table mensagem
  add constraint mensagem_finalidade_check
  check (finalidade in ('LEMBRETE_DE_AGENDAMENTO', 'MARKETING', 'PESQUISA_DE_SATISFACAO',
                        'COMPROVANTE_DE_ATENDIMENTO'));
