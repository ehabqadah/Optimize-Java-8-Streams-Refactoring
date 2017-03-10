package edu.cuny.hunter.streamrefactoring.core.analysis;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.ibm.safe.ICFGSupergraph;
import com.ibm.safe.dfa.DFASpec;
import com.ibm.safe.dfa.DFAState;
import com.ibm.safe.dfa.DFATransition;
import com.ibm.safe.dfa.IDFAState;
import com.ibm.safe.dfa.IDFATransition;
import com.ibm.safe.dfa.events.IDispatchEvent;
import com.ibm.safe.dfa.events.IDispatchEventImpl;
import com.ibm.safe.internal.exceptions.MaxFindingsException;
import com.ibm.safe.internal.exceptions.PropertiesException;
import com.ibm.safe.internal.exceptions.SetUpException;
import com.ibm.safe.internal.exceptions.SolverTimeoutException;
import com.ibm.safe.options.WholeProgramProperties;
import com.ibm.safe.properties.PropertiesManager;
import com.ibm.safe.reporting.message.AggregateSolverResult;
import com.ibm.safe.rules.TypestateRule;
import com.ibm.safe.typestate.core.BenignOracle;
import com.ibm.safe.typestate.core.TypeStateProperty;
import com.ibm.safe.typestate.core.TypeStateResult;
import com.ibm.safe.typestate.options.TypeStateOptions;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cfg.BasicBlockInContext;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.intset.IntSet;

import edu.cuny.hunter.streamrefactoring.core.safe.InstructionBasedSolver;
import edu.cuny.hunter.streamrefactoring.core.safe.ModifiedBenignOracle;
import edu.cuny.hunter.streamrefactoring.core.safe.TypestateSolverFactory;
import edu.cuny.hunter.streamrefactoring.core.wala.EclipseProjectAnalysisEngine;

class StateMachine {

	/**
	 * The stream that this state machine represents.
	 */
	private final Stream stream;

	/**
	 * Constructs a new {@link StateMachine} given a {@link Stream} to
	 * represent.
	 * 
	 * @param stream
	 *            The representing stream.
	 */
	StateMachine(Stream stream) {
		this.stream = stream;
	}

	public void start() throws IOException, CoreException, CallGraphBuilderCancelException, CancelException,
			InvalidClassFileException, PropertiesException {
		EclipseProjectAnalysisEngine<InstanceKey> engine = this.getStream().getAnalysisEngine();

		// FIXME: Do we want a different entry point?
		DefaultEntrypoint entryPoint = new DefaultEntrypoint(this.getStream().getEnclosingMethodReference(),
				this.getStream().getClassHierarchy());
		Set<Entrypoint> entryPoints = Collections.singleton(entryPoint);

		// FIXME: Do we need to build a new call graph for each entry point?
		// Doesn't make sense. Maybe we need to collect all enclosing
		// methods and use those as entry points.
		engine.buildSafeCallGraph(entryPoints);
		// TODO: Can I slice the graph so that only nodes relevant to the
		// instance in question are present?
		// TODO: Don't we already have a call graph?

		BenignOracle ora = new ModifiedBenignOracle(engine.getCallGraph(), engine.getPointerAnalysis());

		PropertiesManager manager = PropertiesManager.initFromMap(Collections.emptyMap());
		PropertiesManager.registerProperties(
				new PropertiesManager.IPropertyDescriptor[] { WholeProgramProperties.Props.LIVE_ANALYSIS });
		TypeStateOptions typeStateOptions = new TypeStateOptions(manager);
		typeStateOptions.setBooleanValue(WholeProgramProperties.Props.LIVE_ANALYSIS.getName(), false);

		TypeReference typeReference = this.getStream().getTypeReference();
		IClass streamClass = engine.getClassHierarchy().lookupClass(typeReference);

		TypestateRule rule = new TypestateRule();
		rule.addType(streamClass.getName().toString());
		rule.setName("execution mode and ordering");

		addAutomaton(rule);

		TypeStateProperty dfa = new TypeStateProperty(rule, engine.getClassHierarchy());

		InstructionBasedSolver solver = TypestateSolverFactory.getSolver(engine.getCallGraph(),
				engine.getPointerAnalysis(), engine.getHeapGraph(), dfa, ora, typeStateOptions, null, null, null,
				this.getStream().getInstructionForCreation().get());

		AggregateSolverResult result;
		try {
			result = (AggregateSolverResult) solver.perform(new NullProgressMonitor());
		} catch (SolverTimeoutException | MaxFindingsException | SetUpException | WalaException e) {
			throw new RuntimeException("Exception caught during typestate analysis.", e);
		}

		InstanceKey streamInstanceKey = this.getStream().getInstanceKey(solver.getTrackedInstances(),
				solver.getCallGraph());
		TypeStateResult instanceResult = (TypeStateResult) result.getInstanceResult(streamInstanceKey);

		ICFGSupergraph supergraph = instanceResult.getSupergraph();
		// This doesn't make a whole lot of sense. Only looking at the node of where the stream was declared:
		Set<CGNode> cgNodes = engine.getCallGraph().getNodes(this.getStream().getEnclosingMethodReference());

		cgNodes.forEach(cgNode -> {
			BasicBlockInContext<IExplodedBasicBlock> node = supergraph.getLocalBlock(cgNode, 20);
			IntSet resultIntSet = instanceResult.getResult().getResult(node);

			// print out the facts at the node.
			resultIntSet.foreach(i -> System.out.println(instanceResult.getDomain().getMappedObject(i)));
		});

	}

	protected Stream getStream() {
		return stream;
	}

	private static void addAutomaton(TypestateRule rule) {
		DFASpec automaton = new DFASpec();

		// a bottom state result would need to defer to the initial stream
		// ordering, which is in the field of the stream. TODO: Perhaps we
		// should rename that to initial ordering.
		IDFAState bottomBottomState = addState(automaton, "bottomBottom", true);

		IDFAState sequentialBottomState = addState(automaton, "sequentialBottom");
		IDFAState parallelBottomState = addState(automaton, "parallelBottom");
		IDFAState bottomOrderedState = addState(automaton, "bottomOrdered");
		IDFAState bottomUnorderedState = addState(automaton, "bottomUnordered");

		IDFAState sequentialOrderedState = addState(automaton, "sequentialOrdered");
		IDFAState sequentialUnorderedState = addState(automaton, "sequentialUnordered");
		IDFAState parallelOrderedState = addState(automaton, "parallelOrdered");
		IDFAState parallelUnorderedState = addState(automaton, "parallelUnordered");

		IDispatchEvent parallelEvent = addEvent(automaton, "parallel", ".*parallel\\(\\).*");
		IDispatchEvent sequentialEvent = addEvent(automaton, "sequential", ".*sequential\\(\\).*");
		IDispatchEvent sortedEvent = addEvent(automaton, "sorted", ".*sorted\\(\\).*");
		IDispatchEvent unorderedEvent = addEvent(automaton, "unordered", ".*unordered\\(\\).*");

		// TODO: Need to add concat().
		addTransition(automaton, bottomBottomState, parallelBottomState, parallelEvent);
		addTransition(automaton, bottomBottomState, sequentialBottomState, sequentialEvent);
		addTransition(automaton, bottomBottomState, bottomOrderedState, sortedEvent);
		addTransition(automaton, bottomBottomState, bottomUnorderedState, unorderedEvent);

		addTransition(automaton, sequentialBottomState, parallelBottomState, parallelEvent);
		addTransition(automaton, sequentialBottomState, sequentialBottomState, sequentialEvent);
		addTransition(automaton, sequentialBottomState, sequentialOrderedState, sortedEvent);
		addTransition(automaton, sequentialBottomState, sequentialUnorderedState, unorderedEvent);
		
		addTransition(automaton, parallelBottomState, parallelBottomState, parallelEvent);
		addTransition(automaton, parallelBottomState, sequentialBottomState, sequentialEvent);
		addTransition(automaton, parallelBottomState, parallelOrderedState, sortedEvent);
		addTransition(automaton, parallelBottomState, parallelUnorderedState, unorderedEvent);
		
		addTransition(automaton, bottomUnorderedState, parallelUnorderedState, parallelEvent);
		addTransition(automaton, bottomUnorderedState, sequentialUnorderedState, sequentialEvent);
		addTransition(automaton, bottomUnorderedState, bottomOrderedState, sortedEvent);
		addTransition(automaton, bottomUnorderedState, bottomUnorderedState, unorderedEvent);
		
		addTransition(automaton, bottomOrderedState, parallelOrderedState, parallelEvent);
		addTransition(automaton, bottomOrderedState, sequentialOrderedState, sequentialEvent);
		addTransition(automaton, bottomOrderedState, bottomOrderedState, sortedEvent);
		addTransition(automaton, bottomOrderedState, bottomUnorderedState, unorderedEvent);
		
		addTransition(automaton, sequentialUnorderedState, parallelUnorderedState, parallelEvent);
		addTransition(automaton, sequentialUnorderedState, sequentialUnorderedState, sequentialEvent);
		addTransition(automaton, sequentialUnorderedState, sequentialOrderedState, sortedEvent);
		addTransition(automaton, sequentialUnorderedState, sequentialUnorderedState, unorderedEvent);
		
		addTransition(automaton, sequentialOrderedState, parallelOrderedState, parallelEvent);
		addTransition(automaton, sequentialOrderedState, sequentialOrderedState, sequentialEvent);
		addTransition(automaton, sequentialOrderedState, sequentialOrderedState, sortedEvent);
		addTransition(automaton, sequentialOrderedState, sequentialUnorderedState, unorderedEvent);
		
		addTransition(automaton, parallelUnorderedState, parallelUnorderedState, parallelEvent);
		addTransition(automaton, parallelUnorderedState, sequentialUnorderedState, sequentialEvent);
		addTransition(automaton, parallelUnorderedState, parallelOrderedState, sortedEvent);
		addTransition(automaton, parallelUnorderedState, parallelUnorderedState, unorderedEvent);
		
		addTransition(automaton, parallelOrderedState, parallelOrderedState, parallelEvent);
		addTransition(automaton, parallelOrderedState, sequentialOrderedState, sequentialEvent);
		addTransition(automaton, parallelOrderedState, parallelOrderedState, sortedEvent);
		addTransition(automaton, parallelOrderedState, parallelUnorderedState, unorderedEvent);

		rule.setTypeStateAutomaton(automaton);
	}

	private static IDFATransition addTransition(DFASpec automaton, IDFAState source, IDFAState destination,
			IDispatchEvent event) {
		IDFATransition transition = new DFATransition();
		transition.setSource(source);
		transition.setEvent(event);
		transition.setDestination(destination);
		automaton.addTransition(transition);
		return transition;
	}

	private static IDispatchEvent addEvent(DFASpec automaton, String eventName, String eventPattern) {
		IDispatchEventImpl close = new IDispatchEventImpl();
		close.setName(eventName);
		close.setPattern(eventPattern);
		automaton.addEvent(close);
		return close;
	}

	private static IDFAState addState(DFASpec automaton, String stateName) {
		return addState(automaton, stateName, false);
	}

	private static IDFAState addState(DFASpec automaton, String stateName, boolean initialState) {
		IDFAState state = new DFAState();

		state.setName(stateName);
		automaton.addState(state);

		if (initialState)
			automaton.setInitialState(state);

		return state;
	}
}