-- RT-IAM-010 — dois números por pessoa: o celular pessoal e o do balcão.

-- ===========================================================================
-- O PROBLEMA
--
-- Quem trabalha em salão quase sempre tem dois aparelhos. Até aqui o agente reconhecia UM, e do
-- outro a recepcionista era uma desconhecida: recebia o aviso de RN-CNV-027 dizendo que ali atende
-- um assistente que NÃO atende clientes — o mesmo texto que um estranho lê. O sistema funcionando
-- como escrito, e ela sem meio de descobrir o motivo. É o segundo dia de uso de qualquer salão que
-- tenha um celular no balcão.
--
-- POR QUE UMA TABELA, E NÃO UMA SEGUNDA COLUNA
--
-- `usuario.telefone` + `usuario.telefone_2` daria uma coluna que às vezes significa "o telefone" e
-- às vezes "um dos telefones", e toda consulta passaria a ter de lembrar das duas. Manter a coluna
-- como "principal" e a tabela como "os outros" é a mesma doença com mais passos: a pergunta "este
-- número é dela?" ganharia duas respostas possíveis.
-- ===========================================================================
create table telefone_do_usuario (
  estabelecimento_id uuid not null references estabelecimento(id),
  usuario_id         uuid not null references usuario(id),

  -- E.164 sem o '+', como `usuario.telefone` era e como `cliente.telefone` é. A comparação com o
  -- remetente do WhatsApp é LITERAL (RN-IAM-022): um número com máscara nunca casaria, e a
  -- recepcionista seria tratada como desconhecida sem erro nenhum — indistinguível, olhando o
  -- cadastro, de um número certo.
  telefone           text not null check (telefone ~ '^[0-9]{10,15}$'),

  -- -------------------------------------------------------------------------
  -- RN-IAM-026 — O TETO DE DOIS É FORMA, NÃO CONTAGEM.
  --
  -- Com `count(*) < 2` no caso de uso, duas requisições simultâneas atravessam a contagem juntas e
  -- nenhuma vê a outra — o mesmo TOCTOU que o EXCLUDE da agenda existe para não ter. Trigger é
  -- proibido neste projeto (regra invisível e não testável em unidade). Então a vaga vira coluna:
  -- duas posições, índice único, e o terceiro número não é recusado — é impossível.
  --
  -- A posição NÃO é hierarquia. Não existe "principal" e "reserva": é a vaga. Tirar o da vaga 1 e
  -- pôr outro reaproveita a 1, e isso é invisível para quem usa.
  -- -------------------------------------------------------------------------
  posicao            smallint not null check (posicao in (1, 2)),

  criado_em          timestamptz not null default now(),

  primary key (estabelecimento_id, usuario_id, posicao),

  -- Um número, um dono — POR SALÃO, e incluindo contas inativas (o motivo dos dois está em
  -- RN-IAM-022 e vem de V51). Sem isto, dois usuários com o mesmo telefone tornariam impossível
  -- saber com as permissões de quem o agente age, e a ambiguidade cairia no meio de uma conversa
  -- em vez de no cadastro.
  constraint um_dono_por_numero_no_salao unique (estabelecimento_id, telefone)
);

create index idx_telefone_do_usuario_por_pessoa
  on telefone_do_usuario (estabelecimento_id, usuario_id);

select aplicar_rls_tenant('telefone_do_usuario');

-- ===========================================================================
-- O que existe hoje entra na vaga 1, e a coluna sai.
--
-- Nesta ordem, e no mesmo arquivo: entre o insert e o drop não existe estado em que o telefone de
-- alguém não esteja em lugar nenhum.
-- ===========================================================================
insert into telefone_do_usuario (estabelecimento_id, usuario_id, telefone, posicao)
select estabelecimento_id, id, telefone, 1 from usuario where telefone is not null;

drop index um_telefone_por_usuario_no_salao;
alter table usuario drop constraint telefone_do_usuario_so_digitos;
alter table usuario drop column telefone;
