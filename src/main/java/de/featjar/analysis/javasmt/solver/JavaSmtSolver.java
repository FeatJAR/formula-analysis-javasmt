/* -----------------------------------------------------------------------------
 * formula-analysis-javasmt - Analysis of first-order formulas using JavaSMT
 * Copyright (C) 2021 Sebastian Krieter
 * 
 * This file is part of formula-analysis-javasmt.
 * 
 * formula-analysis-javasmt is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3.0 of the License,
 * or (at your option) any later version.
 * 
 * formula-analysis-javasmt is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with formula-analysis-javasmt. If not, see <https://www.gnu.org/licenses/>.
 * 
 * See <https://github.com/FeatJAR/formula-analysis-javasmt> for further information.
 * -----------------------------------------------------------------------------
 */
package de.featjar.analysis.javasmt.solver;

import java.math.BigInteger;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.BasicLogManager;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.rationals.Rational;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.BasicProverEnvironment;
import org.sosy_lab.java_smt.api.BasicProverEnvironment.AllSatCallback;
import org.sosy_lab.java_smt.api.BooleanFormula;
import org.sosy_lab.java_smt.api.Formula;
import org.sosy_lab.java_smt.api.Model;
import org.sosy_lab.java_smt.api.Model.ValueAssignment;
import org.sosy_lab.java_smt.api.NumeralFormula;
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment;
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment.OptStatus;
import org.sosy_lab.java_smt.api.ProverEnvironment;
import org.sosy_lab.java_smt.api.SolverContext;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;
import org.sosy_lab.java_smt.api.SolverException;

import de.featjar.analysis.solver.MusSolver;
import de.featjar.analysis.solver.OptSolver;
import de.featjar.analysis.solver.SharpSatSolver;
import de.featjar.analysis.solver.SolutionSolver;
import de.featjar.formula.structure.atomic.Assignment;
import de.featjar.formula.structure.atomic.VariableAssignment;
import de.featjar.formula.structure.atomic.literal.VariableMap;
import de.featjar.util.data.Pair;
import de.featjar.util.logging.Logger;

/**
 * SMT solver using JavaSMT.
 *
 * @author Joshua Sprey
 */
public class JavaSmtSolver
	implements SharpSatSolver, SolutionSolver<Object[]>, OptSolver<Rational, Formula>, MusSolver<BooleanFormula> {

	private JavaSmtFormula formula;

	private VariableAssignment assumptions;

	@Override
	public Assignment getAssumptions() {
		return assumptions;
	}

	/**
	 * The current context of the solver. Used by the translator to translate prop4J
	 * nodes to JavaSMT formulas.
	 */
	public SolverContext context;

	public JavaSmtSolver(de.featjar.formula.structure.Formula formula, Solvers solver) {
		try {
			final Configuration config = Configuration.defaultConfiguration();
			final LogManager logManager = BasicLogManager.create(config);
			final ShutdownManager shutdownManager = ShutdownManager.create();
			context = SolverContextFactory.createSolverContext(config, logManager, shutdownManager.getNotifier(),
				solver);
			this.formula = new JavaSmtFormula(context, formula);
			assumptions = new VariableAssignment(formula.getVariableMap().orElseGet(VariableMap::new));
		} catch (final InvalidConfigurationException e) {
			Logger.logError(e);
		}
	}

	@Override
	public BigInteger countSolutions() {
		try (ProverEnvironment prover = context.newProverEnvironment(ProverOptions.GENERATE_ALL_SAT)) {
			for (final BooleanFormula constraint : formula.getConstraints()) {
				prover.addConstraint(constraint);
			}
			addAssumptions(prover);
			return prover.allSat(new AllSatCallback<>() {
				BigInteger count = BigInteger.ZERO;

				@Override
				public void apply(List<BooleanFormula> model) {
					count = count.add(BigInteger.ONE);
				}

				@Override
				public BigInteger getResult() throws InterruptedException {
					return count;
				}
			}, formula.getBooleanVariables());
		} catch (final Exception e) {
			Logger.logError(e);
			return BigInteger.valueOf(-1);
		}
	}

	private void addAssumptions(BasicProverEnvironment<?> prover) throws InterruptedException {
		final FormulaToJavaSmt translator = formula.getTranslator();
		final List<Formula> variables = formula.getVariables();
		for (final Pair<Integer, Object> entry : assumptions.getAll()) {
			final Formula variable = variables.get(entry.getKey());
			if (variable instanceof NumeralFormula) {
				prover.addConstraint(
					translator.createEqual((NumeralFormula) variable, translator.createConstant(entry.getValue())));
			} else {
				if (entry.getValue() == Boolean.FALSE) {
					prover.addConstraint((BooleanFormula) variable);
				} else {
					prover.addConstraint(translator.createNot((BooleanFormula) variable));
				}
			}
		}
	}

	@Override
	public Object[] getSolution() {
		try (ProverEnvironment prover = context.newProverEnvironment()) {
			for (final BooleanFormula constraint : formula.getConstraints()) {
				prover.addConstraint(constraint);
			}
			addAssumptions(prover);
			if (!prover.isUnsat()) {
				final Model model = prover.getModel();
				final Iterator<ValueAssignment> iterator = model.iterator();
				final Object[] solution = new Object[formula.getVariableMap().getVariableCount() + 1];
				while (iterator.hasNext()) {
					final ValueAssignment assignment = iterator.next();
					final int index = formula.getVariableMap().getVariableIndex(assignment.getName()).orElseThrow();
					solution[index] = assignment.getValue();
				}
				return solution;
			} else {
				return null;
			}
		} catch (final SolverException e) {
			return null;
		} catch (final InterruptedException e) {
			return null;
		}
	}

	@Override
	public Object[] findSolution() {
		return getSolution();
	}

	@Override
	public Rational minimum(Formula formula) {
		try (OptimizationProverEnvironment prover = context.newOptimizationProverEnvironment()) {
			for (final BooleanFormula constraint : this.formula.getConstraints()) {
				prover.addConstraint(constraint);
			}
			addAssumptions(prover);
			final int handleY = prover.minimize(formula);
			final OptStatus status = prover.check();
			assert status == OptStatus.OPT;
			final Optional<Rational> lower = prover.lower(handleY, Rational.ofString("1/1000"));
			return lower.orElse(null);
		} catch (final Exception e) {
			Logger.logError(e);
			return null;
		}
	}

	@Override
	public Rational maximum(Formula formula) {
		try (OptimizationProverEnvironment prover = context.newOptimizationProverEnvironment()) {
			for (final BooleanFormula constraint : this.formula.getConstraints()) {
				prover.addConstraint(constraint);
			}
			addAssumptions(prover);
			final int handleX = prover.maximize(formula);
			final OptStatus status = prover.check();
			assert status == OptStatus.OPT;
			final Optional<Rational> upper = prover.upper(handleX, Rational.ofString("1/1000"));
			return upper.orElse(null);
		} catch (final Exception e) {
			Logger.logError(e);
			return null;
		}
	}

	@Override
	public SatResult hasSolution() {
		try (ProverEnvironment prover = context.newProverEnvironment()) {
			for (final BooleanFormula constraint : formula.getConstraints()) {
				prover.addConstraint(constraint);
			}
			addAssumptions(prover);
			return prover.isUnsat() ? SatResult.FALSE : SatResult.TRUE;
		} catch (final SolverException e) {
			return SatResult.TIMEOUT;
		} catch (final InterruptedException e) {
			return SatResult.TIMEOUT;
		}
	}

	@Override
	public List<BooleanFormula> getMinimalUnsatisfiableSubset() throws IllegalStateException {
		try (ProverEnvironment prover = context.newProverEnvironment()) {
			for (final BooleanFormula constraint : formula.getConstraints()) {
				prover.addConstraint(constraint);
			}
			addAssumptions(prover);
			if (prover.isUnsat()) {
				final List<BooleanFormula> formula = prover.getUnsatCore();
				return formula.stream().filter(Objects::nonNull).collect(Collectors.toList());
			}
			return Collections.emptyList();
		} catch (final Exception e) {
			Logger.logError(e);
			return null;
		}
	}

	@Override
	public List<List<BooleanFormula>> getAllMinimalUnsatisfiableSubsets() throws IllegalStateException {
		return Collections.singletonList(getMinimalUnsatisfiableSubset());
	}

	@Override
	public JavaSmtFormula getDynamicFormula() {
		return formula;
	}

	@Override
	public VariableMap getVariables() {
		return formula.getVariableMap();
	}
}
