package br.com.node.kademlia;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import br.com.node.vo.NoRemoto;

public final class TabelaDeRotas {

	private final byte[] nossaChave;
	private final int porBalde;

	private final List<List<NoRemoto>> baldes = new ArrayList<>(Distancia.BITS + 1);

	public TabelaDeRotas(byte[] nossoId, int porBalde) {

		this.nossaChave = Distancia.chave(nossoId);
		this.porBalde = porBalde;

		for (int i = 0; i <= Distancia.BITS; i++) {
			baldes.add(new ArrayList<>());
		}
	}

	public synchronized boolean inserir(NoRemoto no) {

		int balde = Distancia.log(nossaChave, Distancia.chave(no.id()));

		if (balde == 0) {
			return false;
		}

		List<NoRemoto> nos = baldes.get(balde);

		if (nos.contains(no) || nos.size() >= porBalde) {
			return false;
		}

		nos.add(no);

		return true;
	}

	public synchronized List<NoRemoto> maisProximos(byte[] alvo, int quantos) {

		List<NoRemoto> todos = new ArrayList<>();

		for (List<NoRemoto> balde : baldes) {
			todos.addAll(balde);
		}

		byte[] chaveAlvo = Distancia.chave(alvo);

		Map<NoRemoto, byte[]> chaves = new IdentityHashMap<>();

		for (NoRemoto no : todos) {
			chaves.put(no, Distancia.chave(no.id()));
		}

		todos.sort((a, b) -> Distancia.comparar(chaveAlvo, chaves.get(a), chaves.get(b)));

		return List.copyOf(todos.subList(0, Math.min(quantos, todos.size())));
	}

	public synchronized int total() {

		int total = 0;

		for (List<NoRemoto> balde : baldes) {
			total += balde.size();
		}

		return total;
	}
}
