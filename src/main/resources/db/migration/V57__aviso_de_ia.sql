-- RT-CNV-008 — o aviso a quem não é da equipe.

-- ===========================================================================
-- Como clientes falam com o salão (RN-IAM-023)
--
-- Telefone em E.164, só dígitos, ou link https. Opcional: sem ele, o aviso sai sem a linha do contato.
-- A normalização é do domínio; o check é a última linha de defesa contra o que escapou dele.
-- ===========================================================================
alter table estabelecimento add column contato_para_clientes text
  constraint contato_para_clientes_valido check (
    char_length(contato_para_clientes) <= 200
    and (contato_para_clientes ~ '^[0-9]{10,15}$'
         or contato_para_clientes ~ '^https://[^[:space:]]+$'));

-- ===========================================================================
-- Quem já foi avisado (RN-CNV-029)
--
-- Uma linha por número, por salão — e o número não está aqui. `impressao` é um HMAC com a chave do
-- cofre de campo, do salão junto com o telefone: sem a chave, a tabela não diz quem escreveu, e a
-- mesma pessoa não tem a mesma impressão em dois salões.
--
-- `mensagem_id` sem chave estrangeira: a caixa de entrada vale 90 dias, e "já avisado" vale mais.
-- ===========================================================================
create table aviso_de_ia (
  id                  uuid primary key default gen_random_uuid(),
  estabelecimento_id  uuid not null references estabelecimento(id),
  impressao           bytea not null check (octet_length(impressao) = 32),
  mensagem_id         uuid not null,
  avisado_em          timestamptz not null,

  constraint um_aviso_por_numero unique (estabelecimento_id, impressao)
);

select aplicar_rls_tenant('aviso_de_ia');

-- Registro de fato: a aplicação insere e lê. Apagar a linha faria avisar de novo, reescrever não
-- tem sentido.
revoke update, delete on aviso_de_ia from salao_app;

-- ===========================================================================
-- A saída do número leva o aviso
-- ===========================================================================
alter table mensagem_para_equipe drop constraint mensagem_para_equipe_motivo_check;
alter table mensagem_para_equipe add constraint mensagem_para_equipe_motivo_check
  check (motivo in ('RESPOSTA', 'PEDIDO_DE_CONFIRMACAO', 'PEDIDO_DE_ACEITE', 'DESFECHO_DO_ACEITE',
                    'AVISO_DE_IA'));
