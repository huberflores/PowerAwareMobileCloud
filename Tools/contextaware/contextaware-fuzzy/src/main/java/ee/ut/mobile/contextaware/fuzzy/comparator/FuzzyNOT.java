/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ee.ut.mobile.contextaware.fuzzy.comparator;

import ee.ut.mobile.contextaware.fuzzy.rules.FuzzyTerm;


/**
 * @author Root
 */
public class FuzzyNOT implements FuzzyTerm {

	FuzzyTerm term;

	public FuzzyNOT(FuzzyTerm term) {
		this.term = term;
	}

	@Override
	public double getDOM() {
		return 1 - term.getDOM();
	}

	@Override
	public void clearDOM() {
		term.clearDOM();
	}

	@Override
	public void orWithDOM(double val) {
		term.orWithDOM(val);
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("NOT( ");
		builder.append(term);
		builder.append(" )");
		return builder.toString();
	}
}
