-- RT-CNV-002 — o telefone do usuário: é por ele que o agente sabe em nome de quem age.
--
-- docs/05: "o bot age em nome de um usuário resolvido pelo telefone, herdando as permissões
-- dele". Até aqui `usuario` não tinha telefone, e a frase não tinha como ser verdade.

alter table usuario add column telefone text;

-- ---------------------------------------------------------------------------
-- Só dígitos, E.164 sem o '+', como `cliente.telefone`.
--
-- A comparação com o remetente do WhatsApp é LITERAL, e o `from` da Meta chega
-- assim. Um telefone gravado com máscara nunca casaria, e a recepcionista seria
-- tratada como desconhecida pelo agente sem erro nenhum — indistinguível, olhando
-- o cadastro, de um número certo. Quem normaliza é a aplicação (`Telefone`); o
-- banco recusa o que escapar.
-- ---------------------------------------------------------------------------
alter table usuario add constraint telefone_do_usuario_so_digitos
  check (telefone is null or telefone ~ '^[0-9]{10,15}$');

-- ---------------------------------------------------------------------------
-- Um telefone, um usuário — POR SALÃO, e incluindo os inativos.
--
-- Por salão: quem trabalha em dois salões do sistema tem duas contas (o e-mail é
-- global, V7) e o mesmo WhatsApp. O webhook já sabe de qual salão é a mensagem
-- antes de perguntar quem a mandou (RN-CNV-003), então a ambiguidade entre salões
-- não existe.
--
-- Incluindo os inativos: um índice `where ativo` deixaria dar o número de uma
-- conta desativada a outra — e reativar a primeira passaria a falhar com erro de
-- unicidade, num caso de uso que não tem nada a ver com telefone. Para reaproveitar
-- o número, tira-se da conta antiga: um gesto explícito, auditado.
--
-- Sem este índice, dois usuários com o mesmo telefone tornariam impossível saber
-- com as permissões de quem o agente age. A ambiguidade é recusada no cadastro.
-- ---------------------------------------------------------------------------
create unique index um_telefone_por_usuario_no_salao
  on usuario (estabelecimento_id, telefone)
  where telefone is not null;
