# Runbook — importar o extrato da Stone

RT-FIN-009. Quem importa: dono ou gerência (`financeiro:reconcile`, com MFA), em **Gestão → Conciliação**.

## Exportar do portal

No portal da Stone: **Vendas → Lista de vendas**, o período, **Exportar → CSV**. Pix na maquininha não vem
nesse relatório — e não faz falta: Pix não passa pela conciliação.

## Conferir antes de importar

A prévia mostra, ao lado de cada campo, o que foi lido da primeira venda. Conferir três coisas:

1. **O valor bruto é o da venda** — um valor mil vezes maior ou menor é o formato de número trocado:
   clicar em `1.234,56` ou `1,234.56` no topo.
2. **A data e a hora batem com o comprovante.**
3. **Não sobrou obrigatório sem coluna** — Stone ID, data e valor bruto.

O mapeamento confirmado fica salvo; a próxima importação já vem com ele.

## Olhar

```sql
-- as importações do salão, com as contas
select importada_em, nome_do_arquivo, linhas, novas, canceladas, repetidas, ignoradas, recusadas
  from importacao_de_extrato where estabelecimento_id = '<salão>' order by importada_em desc limit 10;

-- as recusas da última
select jsonb_array_elements(recusas) from importacao_de_extrato
 where estabelecimento_id = '<salão>' order by importada_em desc limit 1;

-- vendas da Stone por situação
select situacao, count(*), sum(valor_bruto) from transacao_do_adquirente
 where estabelecimento_id = '<salão>' group by 1;
```

## Modos de falha

**"é uma planilha do Excel"** — exportou em XLS/XLSX. Exportar de novo em CSV.

**413, "passa do limite de envio"** — mais de 5 MB. Exportar um período menor (um mês costuma caber com
folga).

**Muitas recusadas com "valor não reconhecido"** — formato de número trocado. Voltar à prévia e trocar.

**"Stone ID … já importado com valor bruto …"** — a Stone mudou o valor de uma venda já importada, ou o
arquivo é de outra conta. O valor gravado **não** muda sozinho: conferir no portal. Não apagar a linha no
banco para "deixar entrar" — a aplicação nem tem permissão, e a contradição é o que a conciliação existe
para mostrar.

**Todas as linhas repetidas** — o arquivo, ou o período, já tinha sido importado. Nada foi duplicado.

**Coluna que sumiu da sugestão** — o portal mudou o nome. A prévia mostra o campo sem coluna; escolher a
certa. Se for de vez, acrescentar o nome novo aos apelidos de `CampoDoExtrato`.

## A conferência (RT-FIN-010)

Depois de importar, a conferência do mês mostra quatro contas — clique em cada uma para ver a lista:

- **Divergentes** — a mesma venda com algo diferente: a forma registrada (débito × crédito), o valor, ou a
  venda cancelada na Stone depois. Corrigir o pagamento na comanda.
- **Só na maquininha** — venda no cartão sem pagamento no sistema: comanda esquecida, ou dinheiro que
  entrou sem comanda.
- **Só no sistema** — pagamento no cartão sem venda na maquininha: forma registrada errado, ou passado em
  outra máquina.
- **Conciliadas** — batem.

"Conciliar agora" casa o que foi registrado depois da última importação. Pagamentos em dias sem extrato
aparecem num aviso à parte: importe a Lista de vendas desses dias.

```sql
-- os pares do mês, por critério e se divergem
select c.criterio, c.divergencias <> '[]'::jsonb as diverge, count(*)
  from conciliacao_de_venda c join transacao_do_adquirente t on t.id = c.transacao_id
 where t.estabelecimento_id = '<salão>' and t.capturada_em >= '<início do mês>'
 group by 1, 2;
```

**Casou errado?** Em valores iguais, trocar dois pares não muda dinheiro. Um par realmente errado —
valores diferentes — só casa por NSU ou autorização; desfazer com motivo é RT-FIN-011. **Nunca** apagar a
linha no banco.

