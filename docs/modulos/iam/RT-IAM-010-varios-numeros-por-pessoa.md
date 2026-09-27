---
id: RT-IAM-010
titulo: "Dois números por pessoa: o pessoal e o do salão"
modulo: iam
fase: 4
perfil: completo
status: implementado
depende_de: [RT-CNV-002, RT-IAM-007, RT-IAM-009]
permissoes: [usuario:manage]
eventos: []
regras: [RN-IAM-022, RN-IAM-026, RN-CNV-033]
atualizado_em: 2026-09-27
---

# RT-IAM-010 — Dois números por pessoa

## 1. Objetivo

A mesma pessoa, reconhecida pelo agente tanto do celular pessoal quanto do número do salão.

## 2. Contexto de negócio

Quem trabalha em salão quase sempre tem dois aparelhos: o pessoal e o do balcão. Hoje o agente
reconhece **um**. Do outro, a recepcionista é uma desconhecida — e o que ela recebe é o aviso de
RN-CNV-027, dizendo que ali atende um assistente que **não atende clientes**.

É o pior tipo de falha: a mensagem está correta, o sistema está funcionando como escrito, e a pessoa
não tem como descobrir o motivo — o aviso que ela lê é idêntico ao que um estranho lê. Ela conclui
que o agente quebrou.

Não é caso raro. É o segundo dia de uso de qualquer salão que tenha um celular no balcão.

## 3. Atores e permissões

| Quem | O quê | Permissão |
|---|---|---|
| Administrador | liga e desliga números de qualquer conta | `usuario:manage` |

A mesma de hoje, e pelo mesmo motivo (RN-IAM-022): ligar um número **não é cadastro, é conceder
permissão** — dá a quem escreve daquele número tudo o que aquela conta pode fazer pelo agente.

## 4. Pré-condições

- [x] Telefone do usuário, único por salão (RT-CNV-002, `V51`)
- [x] Tela de acessos, onde os números aparecem (RT-IAM-009)
- [x] Identificação por telefone na hora da ação (RN-IAM-019)

## 5. Fluxo

1. Na tela de acessos, a pessoa aparece com os números que tem — nenhum, um ou dois.
2. O administrador acrescenta ou tira um número e salva.
3. O sistema normaliza cada um (E.164, só dígitos, DDD padrão do salão), recusa duplicata dentro do
   salão e grava a lista.
4. A auditoria registra a lista de antes e a de depois, com os números inteiros.
5. A partir dali, o agente reconhece a pessoa pelos dois.

## 6. Fluxos alternativos

**A1 — O número já é de outra conta.** Recusa, nomeando o colega: dentro do mesmo salão isso não é
vazamento, é orientação. Para reaproveitar o número, tira-se da conta antiga primeiro — um gesto
explícito, auditado, como hoje.

**A2 — Um terceiro número.** Recusado. Duas posições por pessoa, e o banco é quem garante.

**A3 — Nenhum número.** Continua válido: telefone é opcional, e quem não tem não usa o agente.

**A4 — Ela pede de um número e confirma do outro.** O código não vale. Ver RN-CNV-033.

## 7. Regras de negócio

| ID | Resumo em uma linha | Garantida em |
|---|---|---|
| **RN-IAM-022** *(revisada)* | Os números da pessoa: só dígitos, únicos por salão, comparação exata | `V69` · `DefinirTelefonesDoUsuarioUseCase` |
| **RN-IAM-026** | Dois por pessoa, e o teto é forma no banco, não contagem no código | `V69` (`posicao` + índice único) |
| **RN-CNV-033** | A confirmação vale no número que pediu, não na pessoa | `ConfirmarPeloCodigo` (comportamento de hoje, agora deliberado) |

## 8. O que este documento decide de desenho

**Tabela própria, e a coluna `usuario.telefone` sai.** Manter a coluna como "o principal" e a tabela
como "os outros" daria dois lugares para a mesma verdade, e a pergunta "este número é dela?" passaria
a ter duas respostas possíveis. Uma coluna que às vezes significa *o* telefone e às vezes *um dos*
telefones é exatamente a que mente depois.

**O teto de dois é FORMA, não contagem.** Uma coluna `posicao smallint check (posicao in (1, 2))`
com índice único em `(estabelecimento_id, usuario_id, posicao)` torna o terceiro número
impossível — sem trigger (proibido neste projeto) e sem `count(*) < 2` no caso de uso, que duas
requisições simultâneas atravessam. É a mesma escolha do `EXCLUDE` da agenda: quantidade garantida
pelo banco, nunca por um `if`.

A posição não tem significado: não é "principal" e "secundário". É a vaga. Tirar o primeiro e pôr
outro deixa a vaga 1 livre e o caso de uso a reaproveita — invisível para quem usa.

**O único por salão continua, e agora vale entre pessoas.** Mesmo motivo de `V51`: dois donos para o
mesmo número tornariam impossível dizer com as permissões de quem o agente age. A ambiguidade é
recusada no cadastro, e não resolvida no meio de uma conversa.

**Um caso de uso que recebe a LISTA, não dois que ligam e desligam.** A tela mostra os dois campos e
salva o que mostra; a auditoria registra `antes` e `depois` como listas, que é a pergunta real —
"quais números esta conta tinha, e quais passou a ter". Dois casos de uso dariam duas linhas de
trilha para um gesto só.

**A confirmação continua presa ao número.** Decisão do dono (27/09/2026), e vira regra escrita
porque até aqui era consequência de implementação, não escolha: o código foi mandado **àquele**
canal e é resposta àquela mensagem. O contrário faria um número ligado hoje confirmar uma gravação
que ele não pediu — e é gravação no domínio.

**Não há rótulo por número.** "Pessoal" e "do salão" seriam duas palavras que ninguém mantém
atualizadas, e o número é o próprio rótulo: quem olha reconhece o seu. Se aparecer confusão de
verdade, o lugar de resolver já existe.

**O aceite da profissional não muda.** Ele sai pelo telefone do módulo `equipe`
(`FerramentaAgendar.aceiteDe`), que é outro cadastro. Esta rotina é sobre **quem entra**, não sobre
para onde o sistema escreve — foi conferido, não presumido.

## 9. Dados — `V69`

```
telefone_do_usuario
  estabelecimento_id  uuid    not null → estabelecimento
  usuario_id          uuid    not null → usuario
  telefone            text    not null  check só dígitos, 10..15
  posicao             smallint not null check (posicao in (1, 2))
  criado_em           timestamptz
  unique (estabelecimento_id, telefone)       -- um número, um dono
  unique (estabelecimento_id, usuario_id, posicao)  -- no máximo dois
```

Os telefones de hoje entram como `posicao = 1`. Depois disso `usuario.telefone` é **removida**, com
o índice e o check de `V51`.

## 10. Testes

| O que prova | Onde |
|---|---|
| A pessoa é reconhecida pelos dois números | `VariosNumerosIT` |
| O terceiro número é recusado pelo banco, não por um `if` | `VariosNumerosIT` |
| O número de outra conta é recusado, nomeando o colega | `VariosNumerosIT` |
| Tirar um número e pôr outro reaproveita a vaga | `VariosNumerosIT` |
| A auditoria guarda as duas listas, inteiras | `VariosNumerosIT` |
| Lista vazia é válida: a conta fica sem agente | `VariosNumerosIT` |
| Sem `usuario:manage` não se liga número nenhum | `VariosNumerosIT` |
| Um salão não vê o número do outro | `VariosNumerosIT` |
| A confirmação não vale no outro número da mesma pessoa | `IntencaoPendenteIT` |
| A tela de acessos mostra e edita os dois | `Acessos.test.tsx` |

**As três travas do banco têm prova que vai por baixo** — `terceiro_numero_recusado`,
`um_dono_por_numero_no_banco` e `so_digitos_no_banco` inserem por SQL, sem passar pelo caso de uso.
Duas delas nasceram de mutações que sobreviveram: o caso de uso normaliza e confere antes, então
lixo nunca chega ao `check`, e a trava existia sem nada garantir que continuaria existindo. Toda
garantia de banco que o caso de uso torna inalcançável precisa de um teste que a alcance.

## 11. O que foi construído

**Banco — `V69`.** `telefone_do_usuario` com as três travas: `posicao in (1, 2)` (o teto como
forma), um dono por número no salão, e só dígitos. Os telefones de hoje entraram na vaga 1 e
`usuario.telefone` **saiu**, com o índice e o check de `V51`.

**`UsuariosJdbc`.** `porTelefone` passa pela tabela nova; o resumo traz os números num `array(...)`
correlacionado — e não num `left join`, que duplicaria a pessoa que tem dois e mostraria a mesma
linha duas vezes na tela de acessos. `definirTelefones` apaga e regrava as vagas na mesma transação.

**`DefinirTelefonesDoUsuarioUseCase`.** Recebe a lista inteira, normaliza, recusa repetido no
próprio pedido e o número que já é de outra conta — nomeando o colega, que dentro do salão é
orientação, não vazamento. Uma linha de trilha por gesto, com as duas listas inteiras.

**Front.** `Usuario.telefones`, `PUT /v1/usuarios/{id}/telefones`, e o segundo campo aparecendo só
quando se pede: campo vazio permanente convida a preencher, e o que se preenche ali dá acesso à
agenda do salão.

**Rodada de mutação: 12/12 mortas**, depois de refeita. Duas sobreviveram na primeira volta e
viraram teste (ver §10).

## 12. Pendências

- **O aceite da profissional continua vindo do módulo `equipe`** (`FerramentaAgendar.aceiteDe`), que
  tem um cadastro de telefone próprio, com uma coluna só. Não é incoerência desta rotina — é outro
  cadastro, para outra finalidade —, mas o dia em que a profissional trocar de aparelho, o pedido de
  aceite vai para o número antigo. Vira rotina quando incomodar.
- **Nada impede o mesmo número em `equipe` e em `iam` apontando para pessoas diferentes.** Hoje são
  mundos separados de propósito. Se um dia se cruzarem, é aqui que a incoerência aparece.
