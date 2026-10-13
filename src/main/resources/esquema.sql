CREATE TABLE  if not exists  peers (
	id bytea NOT NULL,
	host text NOT NULL,
	porta_udp int4 NOT NULL,
	porta_tcp int4 NOT NULL,
	chain int4 NOT NULL,
	veredicto text NULL,
	falhas int4 DEFAULT 0 NOT NULL,
	resposta decimal(5,3)NULL,
	CONSTRAINT peers_pkey PRIMARY KEY (id),
	CONSTRAINT peers_veredicto_valido CHECK (((veredicto IS NULL) OR (veredicto = ANY (ARRAY['BSC'::text, 'OUTRA_REDE'::text, 'CHEIO'::text, 'MUDO'::text, 'INALCANCAVEL'::text]))))
);


INSERT INTO peers (id, host, porta_udp, porta_tcp, chain, veredicto, falhas, resposta)
SELECT decode(id, 'hex'), host, porta_udp, porta_tcp, chain, veredicto, falhas, resposta
FROM (
    VALUES
        ('571BEE8FB902A625942F10A770CCF727AE2BA1BAB2A2B64E121594A99C9437317F6166A395670A00B7D93647EACAFE598B6BBCEF15B40B6D1A10243865A3E80F', '35.73.84.120', 30311, 30311, 56, NULL, 4, 0.2),
        ('FAC42FB0BA082B7D1EEBDED216DB42161163D42E4F52C9E47716946D64468A62DA4BA0B1CAC0DF5E8BF1E5284861D757339751C33D51DFEF318BE5168803D0B5', '18.203.152.54', 30311, 30311, 56, NULL, 1, 0.2),
        ('5DB798DEB67DF75D073F8E2953DAD283148133ACB520625EA804C9C4AD09A35F13592A762D8F89056248F3889F6DCC33490C145774EA4FF2966982294909B37A', '107.20.191.97', 30311, 30311, 56, NULL, 18, 0.2),
        ('AD78C64A4ADE83692488AA42E4C94084516E555D3F340D9802C2BF106A3DF8868BC46EAE083D2DE4018F40E8D9A9952C32A0943CD68855A9BC9FD07AAC982A6D', '34.204.214.24', 30311, 0, 56, NULL, 4, 0.2),
        ('433C8BFDF53A3E2268CCB1B829E47F629793291CBDDF0C76AE626DA802F90532251FC558E2E0D10D6725E759088439BF1CD4714716B03A259A35D4B2E4ACFA7F', '52.69.102.73', 30311, 30311, 56, NULL, 22, 0.2)
) AS v(id, host, porta_udp, porta_tcp, chain, veredicto, falhas, resposta)
WHERE NOT EXISTS (
    SELECT 1
    FROM peers p
    WHERE p.id = decode(v.id, 'hex')   
);

create table if not exists chains (
    id      bigint      primary key,
    nome    text        not null,
    genesis bytea       not null,
    criada_em timestamptz not null default now(),

    constraint chains_genesis_32 check (octet_length(genesis) = 32)
);

insert into chains (id, nome, genesis)
values (56, 'BNB Smart Chain',
        '\x0d21840abff46b96c84b2ac9e10e4f5cdaeb5693cb665db62a2f3b02d2d57b5b')
on conflict (id) do nothing;

-- Arranca a tabela antiga de taxa DECIDIDA (pool, taxa, contraditoria): ela embutia a regra
-- "contradição é permanente", que a medição desmentiu (o K ordena as provas, não as contradiz).
-- Substituída por `provas_de_taxa`, que guarda evidência, não decisão. Fica o `drop` idempotente
-- para nenhuma máquina restar com a decisão velha e ninguém ler dela por engano — nem reserva, nem
-- decisão vão ao banco; só prova (P22).
drop table if exists taxas;

-- A EVIDÊNCIA de taxa de cada pool — nunca a taxa decidida.
--
-- A versão anterior desta tabela guardava a taxa decidida e embutia a regra "contradição é
-- permanente". A regra estava errada: o invariante K só limita a taxa por CIMA, então duas taxas
-- exatas para a mesma pool não se contradizem, se ordenam (2500 provado por vinte chamadores
-- vence 3000 provado por um robô que calcula com a fórmula de 0,3%). E o erro mostrou o
-- problema maior: banco que guarda decisão decide com a regra do dia em que gravou.
--
-- Então aqui só há evidência, e a regra que decide mora num lugar só, `Taxas`, que a reaplica ao
-- semear. A tabela antiga `taxas` não é mais criada; ela não é apagada daqui porque apagar dado é
-- decisão de quem opera o banco, não de uma migração que roda sozinha.
--
-- Regras que ficam no banco, por serem do domínio e não da regra de decisão:
--   * taxa redonda: múltiplo de um ponto-base. Taxa quebrada é estado errado absorvido pela taxa;
--   * chamador faz parte da chave: sem ele, "provado por dois chamadores" não se reaplica;
--   * gravar SOMA fechamentos, nunca substitui.
create table if not exists provas_de_taxa (
    pool         bytea   not null,
    taxa         int4    not null,
    chamador     bytea   not null,
    fechamentos  int8    not null,

    constraint provas_de_taxa_pkey primary key (pool, taxa, chamador),
    constraint provas_de_taxa_pool_20 check (octet_length(pool) = 20),
    constraint provas_de_taxa_chamador_20 check (octet_length(chamador) = 20),
    constraint provas_de_taxa_redonda check (taxa % 100 = 0 and taxa >= 0 and taxa < 1000000),
    constraint provas_de_taxa_fechamentos check (fechamentos > 0)
);

-- A prova de IMPOSTO de cada (pool, token) — a metade que faltava do "provado, salvo no banco".
--
-- Imposto é do par de destino (o `_transfer` decide por `automatedMarketMakerPairs[to]`), então a chave
-- é (pool, token), não o token. bps é o que a sonda mediu transferindo direto ao par; -1 é honeypot,
-- transferência que reverte. Imutável: grava-se uma vez e vale, e o restart não faz a sonda revetar.
create table if not exists impostos_provados (
    pool  bytea  not null,
    token bytea  not null,
    bps   int4   not null,

    constraint impostos_provados_pkey primary key (pool, token),
    constraint impostos_provados_pool_20 check (octet_length(pool) = 20),
    constraint impostos_provados_token_20 check (octet_length(token) = 20),
    constraint impostos_provados_bps check (bps >= -1 and bps < 1000000)
);

-- O imposto de COMPRA de cada (pool, token): o que o token perde quando o PAR o envia. A tabela de cima
-- guarda a VENDA (transferir ao par; -1 = honeypot). Até 27/09 as duas direções iam para a de cima, e a
-- linha só dizia "taxado ou não" — servia para barrar a perna, não para fazer a conta dela.
create table if not exists impostos_de_compra (
    pool  bytea  not null,
    token bytea  not null,
    bps   int4   not null,

    constraint impostos_de_compra_pkey primary key (pool, token),
    constraint impostos_de_compra_pool_20 check (octet_length(pool) = 20),
    constraint impostos_de_compra_token_20 check (octet_length(token) = 20),
    constraint impostos_de_compra_bps check (bps >= 0 and bps < 10000)
);


-- O `PoolKey` das pools que moram num GERENTE (Uniswap V4, PancakeSwap Infinity CL), lido do
-- `Initialize` — emitido UMA vez, quando a pool nasce. Por isso vem de varredura histórica, e fica no
-- banco para a varredura não se repetir e para viajar com o node sem depender de arquivo copiado no
-- deploy (o `quarentena.json` do servidor subiu sem as taxas medidas em 25/09).
--
-- `taxa_lp` é a de IDENTIDADE (0x800000 = dinâmica), não a cobrada: a cobrada vem do `Swap`.
-- `permissoes` são os bits de hook (V4: 14 bits baixos do endereço do hook; Infinity: `parameters`).
create table if not exists pools_no_gerente (
    gerente     bytea  not null,
    pool_id     bytea  not null,
    token0      bytea  not null,
    token1      bytea  not null,
    taxa_lp     int4   not null,
    espacamento int4   not null,
    hooks       bytea  not null,
    permissoes  int4   not null,
    altura      int8   not null,

    constraint pools_no_gerente_pkey primary key (gerente, pool_id),
    constraint pools_no_gerente_gerente_20 check (octet_length(gerente) = 20),
    constraint pools_no_gerente_pool_32 check (octet_length(pool_id) = 32),
    constraint pools_no_gerente_tokens_20 check (octet_length(token0) = 20 and octet_length(token1) = 20),
    constraint pools_no_gerente_hooks_20 check (octet_length(hooks) = 20),
    constraint pools_no_gerente_espacamento check (espacamento between 1 and 32767)
);

-- Até que bloco a varredura de cada gerente já passou. Retomar daqui é o que torna a varredura barata
-- depois da primeira vez (~3.100 chamadas de 50 mil blocos em 25/09).
create table if not exists varredura_de_gerente (
    gerente bytea not null,
    ate     int8  not null,

    constraint varredura_de_gerente_pkey primary key (gerente),
    constraint varredura_de_gerente_20 check (octet_length(gerente) = 20)
);
