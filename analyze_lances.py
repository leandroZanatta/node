import json, sys
from collections import Counter, defaultdict

def load_lances(path):
    lances = []
    with open(path) as f:
        for line in f:
            lances.append(json.loads(line))
    return lances

def resumir_desfechos(lances):
    desfechos = Counter()
    aceitos = Counter()
    for d in lances:
        df = d.get('desfecho', {}).get('classe', 'desconhecido')
        desfechos[df] += 1
        envio = d.get('envio', {})
        if envio.get('submissao_aceita') or envio.get('relay_aceitou'):
            aceitos['aceito'] += 1
        else:
            aceitos['rejeitado'] += 1
    print(f"Total lances: {len(lances)}")
    print(f"\nDesfechos por classe:")
    for k, v in desfechos.most_common():
        print(f"  {k}: {v}")
    print(f"\nAceitos/Rejeitados (por submissao/relay):")
    for k, v in aceitos.most_common():
        print(f"  {k}: {v}")
    return desfechos

def analisar_lances_por_rota(lances):
    """Agrupar lances por rota_id e resumir o lance mais alto ofertado"""
    rotas = defaultdict(list)
    for d in lances:
        envio = d.get('envio', {})
        rota_id = envio.get('rota_id', 'sem-rota')
        lance = envio.get('lance', {})
        rotas[rota_id].append({
            'd': d,
            'alvo': lance.get('alvo_wei_por_gas', '0'),
            'ofertado': lance.get('ofertado_total_wei_por_gas', '0'),
            'pagamento': lance.get('pagamento_direto_builder_bnb_wei', '0'),
            'limitado_por_lucro': lance.get('limitado_por_lucro', False),
            'limitado_por_saldo': lance.get('limitado_por_saldo', False),
            'solicitado_ao_builder': lance.get('solicitado_ao_builder', False),
            'lucro_previsto': lance.get('lucro_previsto_ativo_wei', '0'),
            'saida_prevista': lance.get('saida_prevista_wei', '0'),
            'montante_entrada': lance.get('montante_entrada_wei', '0'),
            'desfecho': d.get('desfecho', {}).get('classe', 'desconhecido'),
        })
    
    print(f"\nTotal de rotas distintas: {len(rotas)}")
    
    # Melhor lance por rota (maior alvo_wei_por_gas)
    print(f"\nTop 20 rotas por lance mais alto ofertado:")
    print(f"{'#':<4} {'rota_id':<40} {'alvo/gas':<15} {'ofertado/gas':<15} {'pagamento':<15} {'limite':<20} {'desfecho'}")
    print('-' * 130)
    cont = 0
    for rota_id, lances_list in sorted(rotas.items(), key=lambda x: -max(int(l['alvo']) for l in x[1])):
        melhor = max(lances_list, key=lambda l: int(l['alvo']))
        cont += 1
        if cont > 20:
            break
        limite = f"lucro={bool(melhor['limitado_por_lucro'])} saldo={bool(melhor['limitado_por_saldo'])}"
        print(f"{cont:<4} {rota_id[:38]:<40} {melhor['alvo']:<15} {melhor['ofertado']:<15} {melhor['pagamento']:<15} {limite:<20} {melhor['desfecho']}")
    return rotas

def analisar_lances_vencedores(rotas):
    """Identificar lances que foram aceitos e mostrar o pagamento"""
    print(f"\n\n=== LANCES ACEITOS (vencedores) ===")
    aceitos = []
    for rota_id, lances_list in rotas.items():
        for lance in lances_list:
            if lance['desfecho'] in ('aceito', 'aceite') or lance['d'].get('envio', {}).get('submissao_aceita') or lance['d'].get('envio', {}).get('relay_aceitou'):
                aceitos.append((rota_id, lance))
    
    if not aceitos:
        print("Nenhum lance aceito encontrado.")
        return
    
    print(f"Total de lances aceitos: {len(aceitos)}")
    print(f"{'#':<4} {'rota_id':<45} {'alvo/gas':<15} {'ofertado/gas':<15} {'pagamento/bnb':<18} {'flag'}")
    print('-' * 130)
    for i, (rota_id, lance) in enumerate(aceitos, 1):
        flag = ''
        if lance['limitado_por_lucro']:
            flag += ' [LUCRO LIMITE]'
        if lance['limitado_por_saldo']:
            flag += ' [SALDO LIMITE]'
        if lance['solicitado_ao_builder']:
            flag += ' [SOLICITOU]'
        print(f"{i:<4} {rota_id[:43]:<45} {lance['alvo']:<15} {lance['ofertado']:<15} {lance['pagamento']:<18} {flag}")

def comparar_lance_certo(rotas):
    """Comparar lances aceitos com o lance do rival (se houver registo)"""
    print(f"\n\n=== COMPARATIVO: lance certo vs lance do rival ===")
    # Verificar se há registros de rival (submissao_aceita, relay_aceitou)
    for rota_id, lances_list in rotas.items():
        aceito_sel = [l for l in lances_list if l['desfecho'] in ('aceito', 'aceite') or l['d'].get('envio', {}).get('submissao_aceita') or l['d'].get('envio', {}).get('relay_aceitou')]
        nao_aceito = [l for l in lances_list if not (l['desfecho'] in ('aceito', 'aceite') or l['d'].get('envio', {}).get('submissao_aceita') or l['d'].get('envio', {}).get('relay_aceitou'))]
        if aceito_sel and nao_aceito:
            melhor = max(aceito_sel, key=lambda l: int(l['alvo']))
            pior = max(nao_aceito, key=lambda l: int(l['alvo']))
            print(f"ROTA {rota_id}:")
            print(f"  [Vencedor] alvo/gas={melhor['alvo']}, ofertado={melhor['ofertado']}, pagamento={melhor['pagamento']}")
            print(f"  [Perdedor] alvo/gas={pior['alvo']}, ofertado={pior['ofertado']}, pagamento={pior['pagamento']}")
            diferenca = int(melhor['alvo']) - int(pior['alvo'])
            print(f"  Diferenca de lance: {diferenca} wei/gas (vencedor paga {diferenca} mais)")

if __name__ == '__main__':
    path = sys.argv[1] if len(sys.argv) > 1 else '/opt/node/target/ensaio.jsonl'
    lances = load_lances(path)
    resumir_desfechos(lances)
    rotas = analisar_lances_por_rota(lances)
    analisar_lances_vencedores(rotas)
    comparar_lance_certo(rotas)
