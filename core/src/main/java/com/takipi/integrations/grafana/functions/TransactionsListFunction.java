package com.takipi.integrations.grafana.functions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.transaction.Stats;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.util.performance.calc.PerformanceState;
import com.takipi.api.client.util.transaction.TransactionUtil;
import com.takipi.common.util.CollectionUtil;
import com.takipi.common.util.Pair;
import com.takipi.integrations.grafana.input.FunctionInput;
import com.takipi.integrations.grafana.input.TransactionsListInput;
import com.takipi.integrations.grafana.input.ViewInput;
import com.takipi.integrations.grafana.output.Series;
import com.takipi.integrations.grafana.settings.GrafanaSettings;
import com.takipi.integrations.grafana.settings.input.SlowdownSettings;
import com.takipi.integrations.grafana.util.EventLinkEncoder;
import com.takipi.integrations.grafana.util.NumberUtil;
import com.takipi.integrations.grafana.util.TimeUtil;

public class TransactionsListFunction extends GrafanaFunction {
		
	private static final String MISSING_TIMER_LINK = "missing-timer-event";
	
	public static class Factory implements FunctionFactory {

		@Override
		public GrafanaFunction create(ApiClient apiClient) {
			return new TransactionsListFunction(apiClient);
		}

		@Override
		public Class<?> getInputClass() {
			return TransactionsListInput.class;
		}

		@Override
		public String getName() {
			return "transactionsList";
		}
	}
	
	public TransactionsListFunction(ApiClient apiClient) {
		super(apiClient);
	}
	
	private String getTransactionErrorDesc(TransactionData transactionData) {
		
		if (transactionData.errorsHits == 0) {
			return "No errors";
		}
		
		StringBuilder result = new StringBuilder();
		
		result.append(transactionData.errorsHits);
		result.append(" errors from ");
		
		int size = Math.min(transactionData.errors.size(), 3);
		
		transactionData.errors.sort(new Comparator<EventResult>()
		{

			@Override
			public int compare(EventResult o1, EventResult o2)
			{
				return (int)(o2.stats.hits - o1.stats.hits);
			}
		});
		
		Map<String, Long> values = new LinkedHashMap<String, Long>(size); 
		
		
		for (int i = 0; i < size; i++) {
			EventResult error = transactionData.errors.get(i);
			
			if (error.error_location == null) {
				continue;
			}
				
			String key = getSimpleClassName(error.error_location.class_name);
			
			if (key == null) {
				continue;
			}
			
			Long existing = values.get(key);
			
			if (existing != null) {
				values.put(key, existing.longValue() + error.stats.hits);
			} else {
				values.put(key, error.stats.hits);
			}
			
		}
		
		int index = 0;
		
		for (Map.Entry<String, Long> entry : values.entrySet()) {
			
			result.append(entry.getKey());
			
			if (values.size() > 1) {
				result.append("(");
				result.append(entry.getValue());
				result.append(")");
			}
			
			if (index < values.size() - 1) {
				result.append(", ");
			}
			
			index++;
		}
		
		if (transactionData.errors.size() - size > 0) {
			result.append(" and ");
			result.append(transactionData.errors.size() - size );
			result.append(" more locations");
		}
		
		result.append(" in ");
		result.append(transactionData.stats.invocations);
		result.append(" calls");
		
		return result.toString();	
	}

	private List<List<Object>> processServiceTransactions(String serviceId, Pair<DateTime, DateTime> timeSpan,
			TransactionsListInput input, List<String> fields, Collection<PerformanceState> states) {

		TransactionDataResult transactions = getTransactionDatas(serviceId, timeSpan, 
			input, true, input.eventPointsWanted);
		
		if (transactions == null) {
			return Collections.emptyList();
		}
		
		Collection<TransactionData> targets;
		
		if (input.performanceStates != null) {
			List<TransactionData> matchingTransactions = new ArrayList<TransactionData>(transactions.items.size());
			
			for (TransactionData transactionData : transactions.items.values()) {
				if (states.contains(transactionData.state)) {
					matchingTransactions.add(transactionData);
				}
			}
			targets = matchingTransactions;
		} else {
			targets = transactions.items.values();
		}	
		
		List<TransactionData> sorted = new ArrayList<TransactionData>(targets);
		
		sortTransactions(sorted);
	
		List<List<Object>> result = formatResultObjects(sorted, serviceId, timeSpan, input, fields);
		
		return result;	
	}
	
	private void sortTransactions(List<TransactionData> list) {
		
		list.sort(new Comparator<TransactionData>()
		{

			@Override
			public int compare(TransactionData o1, TransactionData o2)
			{
				int diff = o2.state.ordinal() - o1.state.ordinal();
				
				if (diff != 0) {
					return diff;
				}
				
				return (int)(o2.stats.invocations - o1.stats.invocations);
			}
		});
		
	}
	
	private String getTransactionMessage(TransactionData transaction) {
		
		String result = getTransactionName(transaction.graph.name, true);		
		return result;
	}
	
	private String getSlowdownDesc(TransactionData transactionData, 
		SlowdownSettings slowdownSettings, Stats stats) {
				
		Stats baselineStats = TransactionUtil.aggregateGraph(transactionData.baselineGraph);
		double baseline = baselineStats.avg_time_std_deviation * slowdownSettings.std_dev_factor + transactionData.baselineAvg;
		
		StringBuilder result = new StringBuilder();
		
		boolean isSlowdown = (transactionData.state == PerformanceState.CRITICAL) ||
				(transactionData.state == PerformanceState.SLOWING);
		
		if (isSlowdown) {
			
			if  (transactionData.state == PerformanceState.CRITICAL) {
				result.append("Slow: ");		
			} else {
				result.append("Slowing: ");		
			}
			
			result.append("(");	
			result.append((int)(transactionData.score));
			result.append("% of calls > baseline avg ");
			result.append((int)(baselineStats.avg_time));
			result.append("ms + ");
			result.append(slowdownSettings.std_dev_factor);
			result.append("x stdDev = ");
			result.append((int)(baseline));
			result.append("ms");
			result.append(") AND (avg response ");
			result.append((int)(stats.avg_time));
			result.append("ms - ");
			result.append((int)(baselineStats.avg_time));
			result.append("ms baseline > ");
			result.append(slowdownSettings.min_delta_threshold);
			result.append("ms threshold)");			
		} else {
			result.append("OK: Avg response falls within baseline tolerance");
		}
	
		return result.toString();
	}
	
	private Object formatErrorRate(TransactionData transactionData) {
		
		Object result;
		
		if (transactionData.stats.invocations > 0) {
			double errorRate = (double)transactionData.errorsHits / (double)transactionData.stats.invocations;
			
			if (errorRate < 0.01) {
				if (errorRate == 0f) {
					result = Double.valueOf(0);
				} else {
					result = "<1%";
				}
			} else {
				result = Math.min(Math.round(errorRate * 100), 100f);
			}
		} else {
			result = Double.valueOf(0);
		}
		
		return result;
	}
	
	private List<List<Object>> formatResultObjects(Collection<TransactionData> transactions, 
			String serviceId, Pair<DateTime, DateTime> timeSpan,
			TransactionsListInput input, List<String> fields) {
		
		SlowdownSettings slowdownSettings = GrafanaSettings.getData(apiClient, serviceId).slowdown;
		
		List<List<Object>> result = new ArrayList<List<Object>>(transactions.size());

		for (TransactionData transactionData : transactions) {

			String name = getTransactionMessage(transactionData);
			
			Object errorRateValue = formatErrorRate(transactionData);
				
			String link;
			
			if (transactionData.currTimer != null) {
				link = EventLinkEncoder.encodeLink(apiClient, serviceId, input, transactionData.currTimer, 
					timeSpan.getFirst(), timeSpan.getSecond());
			} else {
				link = MISSING_TIMER_LINK;
			}
			
			Pair<Object, Object> fromTo = getTimeFilterPair(timeSpan, input.timeFilter);
			String timeRange = TimeUtil.getTimeRange(input.timeFilter); 
			
			String description = getSlowdownDesc(transactionData, slowdownSettings, transactionData.stats);
			
			Object[] object = new Object[fields.size()];
			
			setOutputObjectField(object, fields, TransactionsListInput.LINK, link);
			setOutputObjectField(object, fields, TransactionsListInput.TRANSACTION, name);
			setOutputObjectField(object, fields, TransactionsListInput.TOTAL, transactionData.stats.invocations);
			setOutputObjectField(object, fields, TransactionsListInput.AVG_RESPONSE, transactionData.stats.avg_time);
			setOutputObjectField(object, fields, TransactionsListInput. BASELINE_AVG, transactionData.baselineAvg);
			setOutputObjectField(object, fields, TransactionsListInput.BASELINE_CALLS, NumberUtil.format(transactionData.baselineInvocations));
			setOutputObjectField(object, fields, TransactionsListInput.ACTIVE_CALLS, NumberUtil.format(transactionData.stats.invocations));
			setOutputObjectField(object, fields, TransactionsListInput.SLOW_STATE, getStateValue(transactionData.state));
			setOutputObjectField(object, fields, TransactionsListInput.DELTA_DESC, description);
			setOutputObjectField(object, fields, TransactionsListInput.ERROR_RATE, errorRateValue);
			setOutputObjectField(object, fields, TransactionsListInput.ERRORS, transactionData.errorsHits);
			setOutputObjectField(object, fields, TransactionsListInput.ERRORS_DESC, getTransactionErrorDesc(transactionData));

			setOutputObjectField(object, fields, ViewInput.FROM, fromTo.getFirst());
			setOutputObjectField(object, fields, ViewInput.TO, fromTo.getSecond());
			setOutputObjectField(object, fields, ViewInput.TIME_RANGE, timeRange);
			

			result.add(Arrays.asList(object));
		}

		return result;
	}

	private int getStateValue(PerformanceState state) {
		
		if (state == null) {
			return 0;
		}
		
		switch (state) {
			
			case SLOWING:
				return 1;
		
			case CRITICAL:
				return 2;
	
			default: 
				return 0;
		}
	}
	
	private void setOutputObjectField(Object[] target, List<String> fields, String field, Object value) {
		
		int index = fields.indexOf(field);
		
		if (index != -1) {
			target[index] = value;
		}
	}
			
	private int getServiceSingleStat(String serviceId, TransactionsListInput input, 
		Pair<DateTime, DateTime> timeSpan, Collection<PerformanceState> states)
	{	
		TransactionDataResult transactionDataResult = getTransactionDatas(serviceId,
			timeSpan, input, false, 0);

		if (transactionDataResult == null) {
			return 0;
		}
		
		int result = 0;
	
		for (TransactionData transactionData : transactionDataResult.items.values()) {
			
			if (states.contains(transactionData.state)) {
				result++;
			}
		}
		
		return result;
	}
	
	private int getSingleStat(Collection<String> serviceIds, 
		TransactionsListInput input, Pair<DateTime, DateTime> timeSpan,
		Collection<PerformanceState> states)
	{
		
		int result = 0;
		
		for (String serviceId : serviceIds)
		{
			result += getServiceSingleStat(serviceId, input, timeSpan, states);
		}
		
		return result;
	}
	
	private List<Series> processSingleStat(TransactionsListInput input, Pair<DateTime, DateTime> timeSpan, Collection<String> serviceIds) {
				
		if (CollectionUtil.safeIsEmpty(serviceIds))
		{
			return Collections.emptyList();
		}
						
		Collection<PerformanceState> performanceStates = TransactionsListInput.getStates(input.performanceStates);
		
		Object singleStatText;
		int singleStatValue = getSingleStat(serviceIds, input, timeSpan, performanceStates);
		
		if (input.singleStatFormat != null)
		{
			if (singleStatValue > 0)
			{
				singleStatText = String.format(input.singleStatFormat, String.valueOf(singleStatValue));
			}
			else
			{
				singleStatText = EMPTY_POSTFIX;
			}
		}
		else
		{
			singleStatText = Integer.valueOf(singleStatValue);
		}
		
		
		return createSingleStatSeries(timeSpan, singleStatText);
	}

	private List<Series> processGrid(TransactionsListInput input, Pair<DateTime, DateTime> timeSpan, Collection<String> serviceIds) {
		
		Series series = new Series();
		
		series.name = SERIES_NAME;
		
		if (input.fields != null) {
			series.columns = Arrays.asList(input.fields.split(ARRAY_SEPERATOR));
		} else {
			series.columns = TransactionsListInput.FIELDS;
		}
		series.values = new ArrayList<List<Object>>();

		Collection<PerformanceState> performanceStates = TransactionsListInput.getStates(input.performanceStates);
		
		for (String serviceId : serviceIds) {
			List<List<Object>> serviceEvents = processServiceTransactions(serviceId, 
				timeSpan, input, series.columns, performanceStates);		
			series.values.addAll(serviceEvents);
		}

		return Collections.singletonList(series);
	}
	
	@Override
	public List<Series> process(FunctionInput functionInput)
	{
		
		if (!(functionInput instanceof TransactionsListInput)) {
			throw new IllegalArgumentException("functionInput");
		}

		TransactionsListInput input = (TransactionsListInput) functionInput;
		
		if (input.renderMode == null)
		{
			throw new IllegalStateException("Missing render mode");
		}
		
		Pair<DateTime, DateTime> timeSpan = TimeUtil.getTimeFilter(input.timeFilter);
		Collection<String> serviceIds = getServiceIds(input);
		
		switch (input.renderMode)
		{
			case Grid:
				return processGrid(input, timeSpan, serviceIds);
				
			default:
				return processSingleStat(input, timeSpan, serviceIds);
			
		}
	}
}
