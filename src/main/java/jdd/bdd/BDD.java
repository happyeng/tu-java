package jdd.bdd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jdd.util.Configuration;
import jdd.util.*;
import jdd.util.math.*;
import org.sngroup.verifier.BDDEngine;
import org.sngroup.verifier.TSBDD;

import java.io.Serializable;
import java.util.Collection;

/**
 * BDD main class with caching removed for apply operations.
 * This version removes caching to reduce memory usage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BDD extends NodeTable implements Cloneable, Serializable {

	public int num_vars, last_sat_vars;
	// Cache removed - only keeping sat_cache as it's for different purpose
	protected DoubleCache sat_cache;

	// quantification stuff
	protected boolean [] varset_vec;
	protected boolean [] sign_vec;
	protected int [] oneSat_buffer;
	private boolean [] support_buffer;
	protected int varset_last, quant_id, quant_cube, restrict_careset;
	protected boolean quant_conj;

	public BDDNames nodeNames = new BDDNames();
	private Permutation firstPermutation;

	@Override
	public Object clone() {
		BDD bddCopy = null;
		try{
			bddCopy = (BDD) super.clone();
			bddCopy.sat_cache = (DoubleCache) this.sat_cache.clone();
			bddCopy.nodeNames = (BDDNames) this.nodeNames.clone();
			bddCopy.nstack = (NodeStack) this.nstack.clone();
			bddCopy.mstack = (NodeStack) this.mstack.clone();
			if(firstPermutation != null) bddCopy.firstPermutation = (Permutation) this.firstPermutation.clone();
		}catch(CloneNotSupportedException e) {
			e.printStackTrace();
		}
		return bddCopy;
	}

	public BDD(){
        super();
    }

	public BDD(int nodesize) {
		this(nodesize, Configuration.DEFAULT_BDD_CACHE_SIZE);
	}

	public BDD(int nodesize, int cache_size) {
		super(Prime.prevPrime(nodesize));

		// Only keep SAT cache
		sat_cache = new DoubleCache("SAT", cache_size / Configuration.bddSatcountDiv);

		num_vars = 0;
		last_sat_vars = -1;
		varset_last = -1;
		varset_vec = Allocator.allocateBooleanArray(24);
		sign_vec = Allocator.allocateBooleanArray(varset_vec.length);
		support_buffer = new boolean[24];

		firstPermutation = null;
		enableStackMarking();
	}

	public BDD(int nodesize, int cache_size, BDDEngine srcBdd, boolean isCopy) {
		super(Prime.prevPrime(nodesize));
		BDD original = srcBdd.bdd.bdd;
		this.table_size = original.table_size;
		this.stat_nt_grow = original.stat_nt_grow;
		this.dead_nodes = original.dead_nodes;
		this.nodesminfree = original.nodesminfree;

		// Deep copy of arrays
		this.t_nodes = original.t_nodes.clone();
		this.t_list = original.t_list.clone();
		this.t_ref = original.t_ref.clone();

		this.first_free_node = original.first_free_node;
		this.free_nodes_count = original.free_nodes_count;
		this.stack_marking_enabled = original.stack_marking_enabled;

		// GC/grow stuff
		this.stat_gc_count = original.stat_gc_count;
		this.stat_lookup_count = original.stat_lookup_count;
		this.stat_gc_freed = original.stat_gc_freed;
		this.stat_gc_time = original.stat_gc_time;
		this.stat_grow_time = original.stat_grow_time;
		this.stat_notify_time = original.stat_notify_time;
		this.ht_chain = original.ht_chain;

		//Node Stack
		this.nstack = new NodeStack(32);
		this.nstack = (NodeStack) original.nstack.clone();
		this.mstack = new NodeStack(32);
		this.mstack = (NodeStack) original.mstack.clone();

		// Only keep SAT cache
		sat_cache = new DoubleCache("SAT", cache_size / Configuration.bddSatcountDiv);
		this.sat_cache = (DoubleCache) original.sat_cache.clone();

		num_vars = 0;
		last_sat_vars = -1;
		varset_last = -1;
		varset_vec = Allocator.allocateBooleanArray(24);
		sign_vec = Allocator.allocateBooleanArray(varset_vec.length);
		support_buffer = new boolean[24];
		firstPermutation = null;

		this.num_vars = original.num_vars;
		this.last_sat_vars = original.last_sat_vars;
		this.varset_last = original.varset_last;
		this.varset_vec = original.varset_vec;
		this.sign_vec = original.sign_vec;
		this.support_buffer = original.support_buffer;

		enableStackMarking();
	}

	public void cleanup() {
		super.cleanup();
		sign_vec = varset_vec = null;
		oneSat_buffer = null;
		sat_cache = null;
	}

	public final int getOne() { return 1; }
	public final int getZero() { return 0; }
	public int numberOfVariables() { return num_vars; }

	public int createVar() {
		int var = nstack.push( mk(num_vars, 0, 1) );
		int nvar = mk(num_vars, 1, 0);
		nstack.pop();
		num_vars++;

		saturate(var);
		saturate(nvar);

		nstack.grow(6 * num_vars + 1);

		if(varset_vec.length < num_vars) {
			varset_vec = Allocator.allocateBooleanArray(num_vars * 3);
			sign_vec = Allocator.allocateBooleanArray(num_vars * 3);
		}

		if(support_buffer.length < num_vars)
			support_buffer = new boolean[num_vars * 3];

		tree_depth_changed(num_vars);

		setAll(0, num_vars, 0, 0);
		setAll(1, num_vars, 1, 1);

		return var;
	}

	public int [] createVars(int n) {
		int [] ret = new int[n];
		for(int i = 0; i < n; i++) {
			ret[i] = createVar();
		}
		return ret;
	}

	protected void post_removal_callbak() {
		sat_cache.invalidate_cache();
	}

	public int mk(int i, int l, int h) {
		if(l == h) return l;
		return add(i,l,h);
	}

	public final int cube(boolean [] v) {
		int last = 1, len = Math.min(v.length, num_vars);
		for(int i = 0; i < len; i++) {
			int var = len - i - 1;
			nstack.push(last);
			if(v[var]) last = mk(var, 0, last);
			nstack.pop();
		}
		return last;
	}

	public final int cube(String s) {
		int len = s.length(), last = 1;
		for(int i = 0; i < len;i++) {
			int var = len - i - 1;
			nstack.push(last);
			if(s.charAt(var) == '1') last = mk(var, 0, last);
			nstack.pop();
		}
		return last;
	}

	public final int minterm(boolean [] v) {
		int last = 1, len = Math.min(v.length, num_vars);
		for(int i = 0; i < len; i++) {
			int var = len - i - 1;
			nstack.push(last);
			last = (v[var] ? mk(var, 0, last) : mk(var, last, 0));
			nstack.pop();
		}
		return last;
	}

	public final int minterm(String s) {
		int len = s.length(), last = 1;
		for(int i = 0; i < len;i++) {
			int var = len - i - 1;
			nstack.push(last);
			last = ((s.charAt(var) == '1') ? mk(var, 0, last) :
					( (s.charAt(var) == '0') ? mk(var, last, 0) : last));
			nstack.pop();
		}
		return last;
	}

	public int ite(int f, int then_, int else_ ) {
		if(f == 0) return else_;
		if(f == 1) return then_;

		if((getLow(f) < 2 && getHigh(f) < 2) && (getVar(f) < getVar(then_)) && (getVar(f) < getVar(else_))) {
			if(getLow(f) == 0) return mk(getVar(f), else_, then_);
			if(getLow(f) == 1) mk(getVar(f), then_, else_ );
		}
		return ite_rec(f, then_, else_);
	}

	private final int ite_rec(int f, int g, int h) {
		if(f == 1) return g;
		if(f == 0) return h;
		if(g == h) return g;
		if(g == 1 && h == 0) return f;
		if(g == 0 && h == 1) return not_rec(f);

		if(g == 1) return or_rec(f,h);
		if(g == 0) {
			int tmp = nstack.push(not_rec(h));
			tmp = nor_rec(f,tmp);
			nstack.pop();
			return tmp;
		}

		if(h == 0) return and_rec(f,g);
		if(h == 1) {
			int tmp = nstack.push( not_rec(g) );
			tmp = nand_rec(f,tmp);
			nstack.pop();
			return tmp;
		}

		// No cache lookup/insert
		int v = Math.min(getVar(f), Math.min(getVar(g), getVar(h)));
		int l = nstack.push( ite_rec(
				(v == getVar(f)) ? getLow(f) : f, (v == getVar(g)) ? getLow(g) : g, (v == getVar(h)) ? getLow(h) : h));

		int H = nstack.push(ite_rec(
				(v == getVar(f)) ? getHigh(f) : f, (v == getVar(g)) ? getHigh(g) : g, (v == getVar(h)) ? getHigh(h) : h) );

		l = mk(v,l,H);
		nstack.drop(2);

		return l;
	}

	public int and(int u1, int u2) {
		nstack.push(u1);
		nstack.push(u2);
		int ret = and_rec(u1,u2);
		nstack.drop(2);
		return ret;
	}

	private final int and_rec(int u1, int u2) {
		if(u1 == u2 || u2 == 1) return u1;
		if(u1 == 0 || u2 == 0) return 0;
		if(u1 == 1) return u2;

		int l, h, v = getVar(u1);
		if(v > getVar(u2)) {v = u1; u1 = u2; u2 = v; v = getVar(u1);	}

		// No cache lookup/insert
		if( v == getVar(u2)) {
			l = nstack.push(and_rec(getLow(u1), getLow(u2)));
			h = nstack.push(and_rec(getHigh(u1), getHigh(u2)));
		} else {
			l = nstack.push( and_rec(getLow(u1), u2));
			h = nstack.push( and_rec(getHigh(u1), u2));
		}

		if(l != h) l = mk(v,l,h);
		nstack.drop(2);

		return l;
	}

	public int nand(int u1, int u2) {
		nstack.push( u1);
		nstack.push( u2);
		int ret = nand_rec(u1,u2);
		nstack.drop(2);
		return ret;
	}

	private final int nand_rec(int u1, int u2) {
		if(u1 == 0 || u2 == 0) return 1;
		if(u1 == 1 || u1 == u2) return not_rec(u2);
		if(u2 == 1) return not_rec(u1);

		int l, h, v = getVar(u1);
		if(v > getVar(u2)) {v = u1; u1 = u2; u2 = v; v = getVar(u1);}

		// No cache lookup/insert
		if( v == getVar(u2)) {
			l = nstack.push( nand_rec(getLow(u1), getLow(u2)));
			h = nstack.push( nand_rec(getHigh(u1), getHigh(u2)));
		} else {
			l = nstack.push( nand_rec(getLow(u1), u2) );
			h = nstack.push( nand_rec(getHigh(u1), u2));
		}

		if(l != h) l = mk(v,l,h);
		nstack.drop(2);
		return l;
	}

	public int or(int u1, int u2) {
		nstack.push( u1);
		nstack.push( u2);
		int ret = or_rec(u1,u2);
		nstack.drop(2);
		return ret;
	}

	private final int or_rec(int u1, int u2) {
		if (u1 == 1 || u2 == 1) return 1;
		if (u1 == 0 || u1 == u2) return u2;
		if (u2 == 0) return u1;
		int l, h, v = getVar(u1);
		if (v > getVar(u2)) {
			v = u1;
			u1 = u2;
			u2 = v;
			v = getVar(u1);
		}

		// No cache lookup/insert
		if (v == getVar(u2)) {
			l = nstack.push(or_rec(getLow(u1), getLow(u2)));
			h = nstack.push(or_rec(getHigh(u1), getHigh(u2)));
		} else {
			l = nstack.push(or_rec(getLow(u1), u2));
			h = nstack.push(or_rec(getHigh(u1), u2));
		}

		if (l != h) l = mk(v, l, h);
		nstack.drop(2);
		return l;
	}

	public int nor(int u1, int u2) {
		nstack.push( u1);
		nstack.push( u2);
		int ret = nor_rec(u1,u2);
		nstack.drop(2);
		return ret;
	}

	private final int nor_rec(int u1, int u2) {
		if(u1 == 1 || u2 == 1) return 0;
		if(u1 == 0 || u1 == u2) return not_rec(u2);
		if(u2 == 0) return not_rec(u1);

		int l, h, v = getVar(u1);
		if(v > getVar(u2)) {v = u1; u1 = u2; u2 = v; v = getVar(u1);}

		// No cache lookup/insert
		if( v == getVar(u2)) {
			l = nstack.push( nor_rec(getLow(u1), getLow(u2)));
			h = nstack.push( nor_rec(getHigh(u1), getHigh(u2)));
		} else {
			l = nstack.push( nor_rec(getLow(u1), u2));
			h = nstack.push( nor_rec(getHigh(u1), u2));
		}

		if(l != h) l = mk(v,l,h);
		nstack.drop(2);
		return l;
	}

	public int xor(int u1, int u2) {
		nstack.push( u1);
		nstack.push( u2);
		int ret = xor_rec(u1,u2);
		nstack.drop(2);
		return ret;
	}

	private final int xor_rec(int u1, int u2) {
		if(u1 == u2) return 0;
		if(u1 == 0) return u2;
		if(u2 == 0) return u1;
		if(u1 == 1) return not_rec(u2);
		if(u2 == 1) return not_rec(u1);

		int l, h, v = getVar(u1);
		if(v > getVar(u2)) {v = u1; u1 = u2; u2 = v; v = getVar(u1);}

		// No cache lookup/insert
		if( v == getVar(u2)) {
			l = nstack.push( xor_rec(getLow(u1), getLow(u2)));
			h = nstack.push( xor_rec(getHigh(u1), getHigh(u2)));
		} else {
			l = nstack.push( xor_rec(getLow(u1), u2));
			h = nstack.push( xor_rec(getHigh(u1), u2));
		}

		if(l != h) l = mk(v,l,h);
		nstack.drop(2);
		return l;
	}

	public int biimp(int u1, int u2) {
		nstack.push( u1);
		nstack.push( u2);
		int ret = biimp_rec(u1,u2);
		nstack.drop(2);
		return ret;
	}

	private final int biimp_rec(int u1, int u2) {
		if(u1 == u2) return 1;
		if(u1 == 0) return not_rec(u2);
		if(u1 == 1) return u2;
		if(u2 == 0) return not_rec(u1);
		if(u2 == 1) return u1;

		int l, h, v = getVar(u1);
		if(v > getVar(u2)) {v = u1; u1 = u2; u2 = v; v = getVar(u1);}

		// No cache lookup/insert
		if( v == getVar(u2)) {
			l = nstack.push( biimp_rec(getLow(u1), getLow(u2)));
			h = nstack.push( biimp_rec(getHigh(u1), getHigh(u2)));
		} else {
			l = nstack.push( biimp_rec(getLow(u1), u2));
			h = nstack.push( biimp_rec(getHigh(u1), u2));
		}

		if(l != h) l = mk(v,l,h);
		nstack.drop(2);
		return l;
	}

	public int imp(int u1, int u2) {
		nstack.push( u1);
		nstack.push( u2);
		int ret = imp_rec(u1,u2);
		nstack.drop(2);
		return ret;
	}

	private final int imp_rec(int u1, int u2) {
		if(u1 == 0 || u2 == 1 || u1 == u2) return 1;
		if(u1 == 1) return u2;
		if(u2 == 0) return not_rec(u1);

		// No cache lookup/insert
		int l, h, v = getVar(u1);
		if( getVar(u1) == getVar(u2)) {
			l = nstack.push( imp_rec(getLow(u1), getLow(u2)));
			h = nstack.push( imp_rec(getHigh(u1), getHigh(u2)));
		} else if (getVar(u1) < getVar(u2)) {
			l = nstack.push( imp_rec(getLow(u1), u2));
			h = nstack.push( imp_rec(getHigh(u1), u2));
		} else  {
			l = nstack.push( imp_rec(u1, getLow(u2)));
			h = nstack.push( imp_rec(u1, getHigh(u2)));
			v = getVar(u2);
		}
		if(l != h) l = mk(v,l,h);
		nstack.drop(2);
		return l;
	}

	public int not(int u1) {
		nstack.push( u1);
		int ret = not_rec(u1);
		nstack.pop();
		return ret;
	}

	private final int not_rec(int bdd) {
		if(bdd < 2) return (bdd ^ 1);

		// No cache lookup/insert
		int l = nstack.push( not_rec(getLow(bdd)));
		int h = nstack.push( not_rec(getHigh(bdd)));
		if(l != h)  l = mk( getVar(bdd), l, h);
		nstack.drop(2);

		return l;
	}

	private void varset(int bdd) {
		Test.check(bdd > 1, "BAD varset");
		for(int i = num_vars; i != 0; ) varset_vec[--i] = false;

		while( bdd > 1) {
			varset_vec[ varset_last = getVar(bdd) ] = true;
			bdd = getHigh(bdd);
		}
	}

	private void varset_signed(int bdd) {
		Test.check(bdd > 1, "BAD varset");

		for(int i = 0; i < num_vars; i++) varset_vec[i] = false;
		while( bdd > 1) {
			varset_last = getVar(bdd);
			varset_vec[varset_last] = true;
			sign_vec[varset_last] = (getLow(bdd) == 0);
			bdd = getHigh(bdd);
		}
	}

	public int exists(int bdd, int cube) {
		if(cube == 1) return bdd;
		Test.check(cube != 0, "Empty cube");
		quant_conj = false;
		quant_id = 0; // No longer used for caching
		quant_cube = cube;

		varset(cube);
		return quant_rec(bdd);
	}

	public int forall(int bdd, int cube) {
		if(cube == 1) return bdd;
		Test.check(cube != 0, "Empty cube");
		quant_conj = true;
		quant_id = 1; // No longer used for caching
		quant_cube = cube;

		varset(cube);
		return quant_rec(bdd);
	}

	private final int quant_rec(int bdd) {
		int var = getVar(bdd);
		if(bdd < 2 || var > varset_last) return bdd;

		// No cache lookup/insert
		int l = 0;
		if(varset_vec[ var ])  {
			l = getLow(bdd);
			int h = getHigh(bdd);

			if(getVar(h)  > getVar(l)) { l = h; h = getLow(bdd); }

			l = quant_rec( l);

			if((quant_conj == true && l == 0) || (quant_conj == false && l == 1)) {
				// early termination
			} else {
				nstack.push( l);
				h = nstack.push( quant_rec( h));
				l = quant_conj ? and_rec(l,h) : or_rec(l,h);
				nstack.drop(2);
			}
		} else {
			l = nstack.push( quant_rec( getLow(bdd) ));
			int h = nstack.push( quant_rec( getHigh(bdd) ));
			l = mk( var, l, h);
			nstack.drop(2);
		}

		return l;
	}

	public int relProd(int u1, int u2, int c) {
		if(c < 2) return and_rec(u1, u2);

		varset(c);

		quant_conj = false;
		quant_id = 0;
		quant_cube = c;
		return relProd_rec(u1, u2);
	}

	private final int relProd_rec(int u1, int u2) {
		if(u1 == 0 || u2 == 0) return 0;
		if(u1 == u2 || u2 == 1) return quant_rec(u1);
		if(u1 == 1) return quant_rec(u2);

		if(getVar(u1) > varset_last && getVar(u2) > varset_last)  {
			return and_rec(u1, u2);
		}

		if(getVar(u2) < getVar(u1)) { int tmp = u1; u1 = u2; u2 = tmp; }

		// No cache lookup/insert
		int l,h, v = getVar(u1);
		l = relProd_rec(getLow(u1), (v == getVar(u2)) ? getLow(u2)  : u2);

		if(varset_vec[ v ]) {
			if(l == 1) return l;
			if(l == getHigh(u1)) return l;
			if(l == getHigh(u2) && getVar(u2) == v) return l;
		}

		nstack.push(l);
		h = nstack.push( relProd_rec( getHigh(u1), (v == getVar(u2)) ? getHigh(u2) : u2));

		if(l != h) {
			if(varset_vec[ v ]) l = or_rec(l, h);
			else l = mk(v, l ,h);
		}

		nstack.drop(2);
		return l;
	}

	private int [] perm_vec;
	private int perm_last, perm_var, perm_id;

	public Permutation createPermutation( int [] cube_from, int [] cube_to) {
		Permutation perm = Permutation.findPermutation(firstPermutation,cube_from, cube_to);
		if(perm != null) {
			// already exists
		} else {
			perm = new Permutation( cube_from, cube_to, this);
			perm.next = firstPermutation;
			firstPermutation = perm;
		}
		return perm;
	}

	public int replace(int bdd, Permutation perm) {
		perm_vec = perm.perm;
		perm_last = perm.last;
		perm_id   = perm.id;
		int ret = replace_rec( bdd );
		perm_vec = null;
		return ret;
	}

	private final int replace_rec(int bdd) {
		if(bdd < 2 || getVar(bdd) > perm_last)	return bdd;

		// No cache lookup/insert
		int l = nstack.push( replace_rec( getLow(bdd)));
		int h = nstack.push( replace_rec( getHigh(bdd)));

		perm_var = perm_vec[ getVar(bdd) ];
		l = mkAndOrder(l,h);

		nstack.drop(2);
		return l;
	}

	private final int mkAndOrder(int l, int h) {
		int vl = getVar(l);
		int vh = getVar(h);
		if(perm_var < vl && perm_var < vh) return mk(perm_var, l, h);

		Test.check(perm_var != vl && perm_var != vh, "Replacing to a variable already in the BDD");

		int x, y, v = vl;
		if( vl == vh) {
			x = nstack.push( mkAndOrder( getLow(l), getLow(h) ));
			y = nstack.push( mkAndOrder( getHigh(l), getHigh(h) ));
		} else if( vl < vh) {
			x = nstack.push( mkAndOrder( getLow(l), h ));
			y = nstack.push( mkAndOrder( getHigh(l), h ));
		} else {
			x = nstack.push( mkAndOrder( l, getLow(h) ));
			y = nstack.push( mkAndOrder( l, getHigh(h) ));
			v = vh;
		}
		x = mk( v, x, y);
		nstack.drop(2);
		return x;
	}

	public int restrict(int u, int v) {
		if(v == 1) return u;

		varset_signed(v);
		restrict_careset = v;
		return restrict_rec(u);
	}

	private int restrict_rec(int u) {
		if(u < 2 || getVar(u) > varset_last) return u;

		// No cache lookup/insert
		int ret = 0;

		if(varset_vec[ getVar(u)] ) {
			ret = restrict_rec( sign_vec[getVar(u)] ? getHigh(u) : getLow(u) );
		} else {
			int l = nstack.push( restrict_rec( getLow(u) ));
			int h = nstack.push( restrict_rec( getHigh(u) ));
			ret = mk( getVar(u), l, h);
			nstack.drop(2);
		}

		return ret;
	}

	public int simplify(int d, int u) {
		if(d == 0) return 0;
		if(u <  2) return u;

		if(d == 1) {
			int l = nstack.push( simplify(d, getLow(u) ));
			int h = nstack.push( simplify(d, getHigh(u) ));
			h = mk( getVar(u), l, h);
			nstack.drop(2);
			return h;
		} else if( getVar(d) == getVar(u)) {
			if(getLow(d)  == 0) return simplify(getHigh(d), getHigh(u));
			if(getHigh(d) == 0) return simplify( getLow(d),  getLow(u));

			int l = nstack.push( simplify( getLow(d),  getLow(u) ));
			int h = nstack.push( simplify(getHigh(d), getHigh(u) ));

			h = mk( getVar(u), l, h);
			nstack.drop(2);
			return h;
		} else if( getVar(d) < getVar(u)) {
			int l = nstack.push( simplify( getLow(d), u ));
			int h = nstack.push( simplify(getHigh(d), u ));
			h = mk( getVar(d), l, h);
			nstack.drop(2);
			return h;
		} else {
			int l = nstack.push( simplify( d,  getLow(u) ));
			int h = nstack.push( simplify( d, getHigh(u) ));

			h = mk( getVar(u), l, h);
			nstack.drop(2);
			return h;
		}
	}

	public boolean isVariable(int bdd) {
		if(bdd < 2 || bdd > table_size || !isValid(bdd)) return false;
		return (getLow(bdd) == 0 && getHigh(bdd) == 1);
	}

	public double satCount(int bdd) {
		if(last_sat_vars != -1 && last_sat_vars != num_vars) sat_cache.invalidate_cache();
		last_sat_vars = num_vars;

		return Math.pow(2, getVar(bdd)) * satCount_rec(bdd);
	}

	protected double satCount_rec(int bdd) {
		if(bdd < 2) return bdd;

		if(sat_cache.lookup(bdd)) return sat_cache.answer;
		int hash = sat_cache.hash_value;

		int low = getLow(bdd);
		int high = getHigh(bdd);

		double ret = (satCount_rec(low) * Math.pow(2, getVar(low)  - getVar(bdd)  -1)) +
				(satCount_rec(high) * Math.pow(2, getVar(high) - getVar(bdd)  -1));

		sat_cache.insert(hash, bdd, ret);
		return ret;
	}

	private int node_count_int;

	public int nodeCount(int bdd) {
		node_count_int = 0;
		nodeCount_mark(bdd);
		unmark_tree(bdd);
		return node_count_int;
	}

	private final void nodeCount_mark(int bdd) {
		if(bdd < 2) return;

		if( isNodeMarked(bdd)) return;
		mark_node(bdd);
		node_count_int++;
		nodeCount_mark( getLow(bdd) );
		nodeCount_mark( getHigh(bdd) );
	}

	public final int quasiReducedNodeCount(int bdd) {
		if(bdd < 2) return 0;
		return 1 + quasiReducedNodeCount(getLow(bdd)) + quasiReducedNodeCount(getHigh(bdd));
	}

	public int oneSat(int bdd) {
		if( bdd < 2) return bdd;

		if(getLow(bdd) == 0) {
			int high = nstack.push( oneSat(getHigh(bdd)));
			int u = mk( getVar(bdd), 0, high);
			nstack.pop();
			return u;
		} else {
			int low= nstack.push( oneSat(getLow(bdd)));
			int u = mk( getVar(bdd), low, 0);
			nstack.pop();
			return u;
		}
	}

	public int [] oneSat(int bdd, int [] buffer) {
		if(buffer == null) buffer = new int[num_vars];

		oneSat_buffer = buffer;
		Array.set(buffer, -1);
		oneSat_rec(bdd);
		oneSat_buffer = null;
		return buffer;
	}

	protected void oneSat_rec(int bdd) {
		if( bdd < 2) return;

		if(getLow(bdd) == 0) {
			oneSat_buffer[ getVar(bdd) ] = 1;
			oneSat_rec(getHigh(bdd));
		} else {
			oneSat_buffer[ getVar(bdd) ] = 0;
			oneSat_rec(getLow(bdd));
		}
	}

	public int support(int bdd) {
		Array.set(support_buffer, false);

		support_rec(bdd);
		unmark_tree(bdd);
		int ret = cube(support_buffer);
		return ret;
	}

	private final void support_rec(int bdd) {
		if(bdd < 2) return;

		if( isNodeMarked(bdd) ) return;
		support_buffer[ getVar(bdd) ] = true;
		mark_node(bdd);

		support_rec( getLow(bdd) );
		support_rec( getHigh(bdd) );
	}

	public boolean member(int bdd, boolean [] minterm ) {
		while(bdd >= 2)
			bdd = (minterm[getVar(bdd)]) ? getHigh(bdd) : getLow(bdd);
		return (bdd == 0) ? false : true;
	}

	public int orTo(int bdd1, int bdd2) {
		int tmp = ref( or(bdd1, bdd2) );
		deref(bdd1);
		return tmp;
	}

	public int andTo(int bdd1, int bdd2) {
		int tmp = ref( and(bdd1, bdd2) );
		deref(bdd1);
		return tmp;
	}

	public void showStats() {
		super.showStats();
		if(sat_cache != null) sat_cache.showStats();
	}

	public long getMemoryUsage() {
		long ret = super.getMemoryUsage();

		if(varset_vec != null) ret += varset_vec.length * 4;
		if(oneSat_buffer != null) ret += oneSat_buffer.length * 4;
		if(support_buffer != null) ret += support_buffer.length * 1;

		if(sat_cache != null) ret += sat_cache.getMemoryUsage();

		Permutation tmp = firstPermutation ;
		while(tmp != null) {
			ret += tmp.getMemoryUsage();
			tmp = tmp.next;
		}

		return ret;
	}

	public void print(int bdd) {BDDPrinter.print(bdd, this);	}
	public void printDot(String fil, int bdd) {	BDDPrinter.printDot(fil, bdd, this, nodeNames);	}
	public void printSet(int bdd) {	BDDPrinter.printSet(bdd, num_vars, this, null);	}
	public void printCubes(int bdd) {	BDDPrinter.printSet(bdd, num_vars, this, nodeNames);	}
	public void setNodeNames(NodeName nn) { nodeNames = (BDDNames) nn; }
}